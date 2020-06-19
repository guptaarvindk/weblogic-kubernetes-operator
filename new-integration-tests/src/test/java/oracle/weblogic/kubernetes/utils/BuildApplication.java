// Copyright (c) 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes.utils;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1HostPathVolumeSource;
import io.kubernetes.client.openapi.models.V1LocalObjectReference;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1PersistentVolume;
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaim;
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaimSpec;
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaimVolumeSource;
import io.kubernetes.client.openapi.models.V1PersistentVolumeSpec;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1SecretList;
import io.kubernetes.client.openapi.models.V1SecurityContext;
import io.kubernetes.client.openapi.models.V1Volume;
import io.kubernetes.client.openapi.models.V1VolumeMount;
import oracle.weblogic.kubernetes.TestConstants;
import oracle.weblogic.kubernetes.actions.impl.Namespace;
import oracle.weblogic.kubernetes.actions.impl.primitive.Kubernetes;
import org.awaitility.core.ConditionFactory;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static oracle.weblogic.kubernetes.TestConstants.KIND_REPO;
import static oracle.weblogic.kubernetes.TestConstants.OCR_EMAIL;
import static oracle.weblogic.kubernetes.TestConstants.OCR_PASSWORD;
import static oracle.weblogic.kubernetes.TestConstants.OCR_REGISTRY;
import static oracle.weblogic.kubernetes.TestConstants.OCR_SECRET_NAME;
import static oracle.weblogic.kubernetes.TestConstants.OCR_USERNAME;
import static oracle.weblogic.kubernetes.TestConstants.PV_ROOT;
import static oracle.weblogic.kubernetes.actions.ActionConstants.RESOURCE_DIR;
import static oracle.weblogic.kubernetes.actions.ActionConstants.WLS_BASE_IMAGE_NAME;
import static oracle.weblogic.kubernetes.actions.ActionConstants.WLS_BASE_IMAGE_TAG;
import static oracle.weblogic.kubernetes.actions.ActionConstants.WORK_DIR;
import static oracle.weblogic.kubernetes.actions.TestActions.createPersistentVolume;
import static oracle.weblogic.kubernetes.actions.TestActions.createPersistentVolumeClaim;
import static oracle.weblogic.kubernetes.actions.impl.primitive.Kubernetes.listSecrets;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.podReady;
import static oracle.weblogic.kubernetes.extensions.LoggedTest.logger;
import static org.apache.commons.io.FileUtils.copyDirectory;
import static org.apache.commons.io.FileUtils.deleteDirectory;
import static org.awaitility.Awaitility.with;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Utility class to build application.
 */
public class BuildApplication {

  private static String image;
  private static boolean isUseSecret;
  private static final String APPLICATIONS_MOUNT_PATH = "/application";
  private static final String BUILD_SCRIPT = "build_application.sh";
  private static final Path BUILD_SCRIPT_SOURCE_PATH = Paths.get(RESOURCE_DIR, "bash-scripts", BUILD_SCRIPT);

  /**
   * Build application.
   *
   * @param application path of the application source folder
   * @param parameters system properties for ant
   * @param targets ant targets to call
   * @param namespace name of the namespace to use for pvc
   * @throws ApiException when Kubernetes cluster interaction fails
   * @throws IOException when zipping file fails
   * @throws InterruptedException when executing command fails
   */
  public static void buildApplication(Path application, Map<String, String> parameters,
      String targets, String namespace) throws ApiException, IOException, InterruptedException {

    setImage(namespace);

    // Copy the application source directory to PV_ROOT/j2eeapplications/<application_directory_name>
    // This location is mounted in the build pod under /application
    Path tempAppPath = Paths.get(WORK_DIR, "j2eeapplications", application.getFileName().toString());

    // recreate PV_ROOT/j2eeapplications/<application_directory_name>
    logger.info("Deleting and recreating {0}", tempAppPath);
    Files.createDirectories(tempAppPath);
    deleteDirectory(tempAppPath.toFile());
    Files.createDirectories(tempAppPath);

    // copy the application source to PV_ROOT/j2eeapplications/<application_directory_name>
    logger.info("Copying {0} to {1}", application, tempAppPath);
    copyDirectory(application.toFile(), tempAppPath.toFile());

    // copy the build script to PV_ROOT/j2eeapplications/<application_directory_name>
    //Path targetBuildScript = Paths.get(tempAppPath.toString(), BUILD_SCRIPT);
    //logger.info("Copying {0} to {1}", BUILD_SCRIPT_SOURCE_PATH, targetBuildScript);
    //Files.copy(BUILD_SCRIPT_SOURCE_PATH, targetBuildScript);
    Path zipFile = Paths.get(createZipFile(tempAppPath));

    logger.info("Walk directory after copy {0}", tempAppPath);
    FileWalker.walk(tempAppPath.toString());

    // create the persistent volume to make the application archive accessible to pod
    String uniqueName = Namespace.uniqueName();
    String pvName = namespace + "-build-pv-" + uniqueName;
    String pvcName = namespace + "-build-pvc-" + uniqueName;

    Path pvHostPath = Paths.get(PV_ROOT, "j2eeapplications", application.getFileName().toString());
    createPV(pvHostPath, pvName);
    createPVC(pvName, pvcName, namespace);

    // add ant properties to env
    V1Container buildContainer = new V1Container();
    if (parameters != null) {
      StringBuilder params = new StringBuilder();
      parameters.entrySet().forEach((parameter) -> {
        params.append("-D").append(parameter.getKey()).append("=").append(parameter.getValue()).append(" ");
      });
      buildContainer = buildContainer
          .addEnvItem(new V1EnvVar().name("sysprops").value(params.toString()));
    }

    // add targets in env
    if (targets != null) {
      buildContainer = buildContainer
          .addEnvItem(new V1EnvVar().name("targets").value(targets));
    }

    V1Pod webLogicPod = setupWebLogicPod(namespace, pvcName, pvName, buildContainer);
    Kubernetes.copyFileToPod(namespace, webLogicPod.getMetadata().getName(),
        null, zipFile, Paths.get(APPLICATIONS_MOUNT_PATH, zipFile.getFileName().toString()));
    Kubernetes.copyFileToPod(namespace, webLogicPod.getMetadata().getName(),
        null, BUILD_SCRIPT_SOURCE_PATH, Paths.get(APPLICATIONS_MOUNT_PATH,
            BUILD_SCRIPT_SOURCE_PATH.getFileName().toString()));

    Kubernetes.exec(webLogicPod, new String[]{
        "/bin/sh", "/application/" + BUILD_SCRIPT});

    /*
    ExecResult exec = Exec.exec(webLogicPod, null, true,
        "sh -c ls /;"
        + "ls /application;"
        + "cd " + APPLICATIONS_MOUNT_PATH + ";",
        "unzip " + zipFile.getFileName() + ";",
        "cd " + application.getFileName() + ";",
        "sh " + BUILD_SCRIPT + ";");
    logger.info(exec.stdout());
    logger.severe(exec.stderr());
    assertEquals(0, exec.exitValue(), "Build application failed");
     */
  }

  private static void createPV(Path hostPath, String pvName) {
    logger.info("creating persistent volume");
    // a dummy label is added so that cleanup can delete all pvs
    HashMap<String, String> label = new HashMap<String, String>();
    label.put("weblogic.domainUid", "buildjobs");

    V1PersistentVolume v1pv = new V1PersistentVolume()
        .spec(new V1PersistentVolumeSpec()
            .addAccessModesItem("ReadWriteMany")
            .storageClassName("weblogic-build-storage-class")
            .volumeMode("Filesystem")
            .putCapacityItem("storage", Quantity.fromString("2Gi"))
            .persistentVolumeReclaimPolicy("Recycle")
            .accessModes(Arrays.asList("ReadWriteMany"))
            .hostPath(new V1HostPathVolumeSource()
                .path(hostPath.toString())
                .type("DirectoryOrCreate")))
        .metadata(new V1ObjectMeta()
            .name(pvName)
            .labels(label));

    boolean success = assertDoesNotThrow(() -> createPersistentVolume(v1pv),
        "Failed to create persistent volume");
    assertTrue(success, "PersistentVolume creation failed");
  }

  private static void createPVC(String pvName, String pvcName, String namespace) {
    logger.info("creating persistent volume claim");
    // a dummy label is added so that cleanup can delete all pvs
    HashMap<String, String> label = new HashMap<String, String>();
    label.put("weblogic.domainUid", "buildjobs");

    V1PersistentVolumeClaim v1pvc = new V1PersistentVolumeClaim()
        .spec(new V1PersistentVolumeClaimSpec()
            .addAccessModesItem("ReadWriteMany")
            .storageClassName("weblogic-build-storage-class")
            .volumeName(pvName)
            .resources(new V1ResourceRequirements()
                .putRequestsItem("storage", Quantity.fromString("2Gi"))))
        .metadata(new V1ObjectMeta()
            .name(pvcName)
            .namespace(namespace)
            .labels(label));

    boolean success = assertDoesNotThrow(() -> createPersistentVolumeClaim(v1pvc),
        "Failed to create persistent volume claim");
    assertTrue(success, "PersistentVolumeClaim creation failed");
  }

  /**
   * Set the image to use and create secrets if needed.
   *
   * @param namespace namespace in which secrets needs to be created
   */
  private static void setImage(String namespace) {
    //determine if the tests are running in Kind cluster.
    //if true use images from Kind registry
    String ocrImage = WLS_BASE_IMAGE_NAME + ":" + WLS_BASE_IMAGE_TAG;
    if (KIND_REPO != null) {
      image = KIND_REPO + ocrImage.substring(TestConstants.OCR_REGISTRY.length() + 1);
      isUseSecret = false;
    } else {
      // create pull secrets for WebLogic image when running in non Kind Kubernetes cluster
      image = ocrImage;
      boolean secretExists = false;
      V1SecretList listSecrets = listSecrets(namespace);
      if (null != listSecrets) {
        for (V1Secret item : listSecrets.getItems()) {
          if (item.getMetadata().getName().equals(OCR_SECRET_NAME)) {
            secretExists = true;
            break;
          }
        }
      }
      if (!secretExists) {
        CommonTestUtils.createDockerRegistrySecret(OCR_USERNAME, OCR_PASSWORD,
            OCR_EMAIL, OCR_REGISTRY, OCR_SECRET_NAME, namespace);
      }
      isUseSecret = true;
    }
    logger.info("Using image {0}", image);
  }

  /**
   * Create a zip file from a folder.
   *
   * @param dirPath folder to zip
   * @return path of the zipfile
   */
  public static String createZipFile(Path dirPath) {
    String dirName = dirPath.getFileName().toString();
    String zipFileName = dirPath.toString().concat(".zip");
    try {
      final ZipOutputStream outputStream = new ZipOutputStream(new FileOutputStream(zipFileName));
      Files.walkFileTree(dirPath, new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
          try {
            Path targetFile = dirPath.relativize(file);
            outputStream.putNextEntry(new ZipEntry(Paths.get(targetFile.toString()).toString()));
            byte[] bytes = Files.readAllBytes(file);
            outputStream.write(bytes, 0, bytes.length);
            outputStream.closeEntry();
          } catch (IOException e) {
            e.printStackTrace();
          }
          return FileVisitResult.CONTINUE;
        }
      });
      outputStream.close();
    } catch (IOException e) {
      e.printStackTrace();
      return null;
    }
    return zipFileName;
  }

  /**
   * Create a temporary WebLogic pod to build j2ee applications.
   *
   * @param namespace name of the namespace in which to create the temporary pod
   * @param pvcName name of the persistent volume claim
   * @param pvName name of the persistent volume in which the applications need to be built
   * @return V1Pod created pod object
   * @throws ApiException when create pod fails
   */
  private static V1Pod setupWebLogicPod(String namespace, String pvcName, String pvName,
      V1Container container) throws ApiException {

    ConditionFactory withStandardRetryPolicy = with().pollDelay(10, SECONDS)
        .and().with().pollInterval(2, SECONDS)
        .atMost(3, MINUTES).await();

    final String podName = "weblogic-build-pod-" + namespace;
    V1Pod podBody = new V1Pod()
        .spec(new V1PodSpec()
            .initContainers(Arrays.asList(new V1Container()
                .name("fix-pvc-owner")
                .image(image)
                .imagePullPolicy("IfNotPresent")
                .addCommandItem("/bin/sh")
                .addArgsItem("-c")
                .addArgsItem("chown -R 1000:1000 " + APPLICATIONS_MOUNT_PATH)
                .securityContext(new V1SecurityContext()
                    .runAsGroup(0L)
                    .runAsUser(0L))
                .volumeMounts(Arrays.asList(new V1VolumeMount()
                    .name(pvName)
                    .mountPath(APPLICATIONS_MOUNT_PATH)))))
            .containers(Arrays.asList(container
                .name("weblogic-container")
                .image(image)
                .imagePullPolicy("IfNotPresent")
                .volumeMounts(Arrays.asList(
                    new V1VolumeMount()
                        .name(pvName) // mount the persistent volume to /shared inside the pod
                        .mountPath(APPLICATIONS_MOUNT_PATH)))
                .addCommandItem("sleep")
                .addArgsItem("600")))
            .volumes(Arrays.asList(
                new V1Volume()
                    .name(pvName) // the persistent volume that needs to be archived
                    .persistentVolumeClaim(new V1PersistentVolumeClaimVolumeSource()
                        .claimName(pvcName))))
            .imagePullSecrets(isUseSecret
                ? Arrays.asList(new V1LocalObjectReference()
                    .name(OCR_SECRET_NAME))
                : null)) // the persistent volume claim used by the test
        .metadata(new V1ObjectMeta().name(podName))
        .apiVersion("v1")
        .kind("Pod");
    V1Pod wlsPod = Kubernetes.createPod(namespace, podBody);

    withStandardRetryPolicy
        .conditionEvaluationListener(
            condition -> logger.info("Waiting for {0} to be ready in namespace {1}, "
                + "(elapsed time {2} , remaining time {3}",
                podName,
                namespace,
                condition.getElapsedTimeInMS(),
                condition.getRemainingTimeInMS()))
        .until(podReady(podName, null, namespace));

    return wlsPod;
  }

}
