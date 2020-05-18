// Copyright (c) 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes;

import java.util.ArrayList;
import java.util.List;

import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1LocalObjectReference;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1SecretReference;
import oracle.weblogic.domain.AdminServer;
import oracle.weblogic.domain.AdminService;
import oracle.weblogic.domain.Channel;
import oracle.weblogic.domain.Cluster;
import oracle.weblogic.domain.Configuration;
import oracle.weblogic.domain.Domain;
import oracle.weblogic.domain.DomainSpec;
import oracle.weblogic.domain.Model;
import oracle.weblogic.domain.ServerPod;
import oracle.weblogic.kubernetes.annotations.IntegrationTest;
import oracle.weblogic.kubernetes.annotations.Namespaces;
import oracle.weblogic.kubernetes.annotations.tags.MustNotRunInParallel;
import oracle.weblogic.kubernetes.annotations.tags.Slow;
import oracle.weblogic.kubernetes.extensions.LoggedTest;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static oracle.weblogic.kubernetes.TestConstants.ADMIN_PASSWORD_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.ADMIN_PASSWORD_PATCH;
import static oracle.weblogic.kubernetes.TestConstants.ADMIN_SERVER_NAME_BASE;
import static oracle.weblogic.kubernetes.TestConstants.ADMIN_USERNAME_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.ADMIN_USERNAME_PATCH;
import static oracle.weblogic.kubernetes.TestConstants.DOMAIN_API_VERSION;
import static oracle.weblogic.kubernetes.TestConstants.K8S_NODEPORT_HOST;
import static oracle.weblogic.kubernetes.TestConstants.MANAGED_SERVER_NAME_BASE;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_IMAGE_NAME;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_IMAGE_TAG;
import static oracle.weblogic.kubernetes.TestConstants.REPO_SECRET_NAME;
import static oracle.weblogic.kubernetes.actions.TestActions.getDomainCustomResource;
import static oracle.weblogic.kubernetes.actions.TestActions.getPodCreationTimestamp;
import static oracle.weblogic.kubernetes.actions.TestActions.patchDomainCustomResource;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.credentialsValid;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.domainResourceAdminSecretPatched;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.isPodRestarted;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.podRestartVersionUpdated;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.checkPodReady;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.checkServiceExists;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.createDockerRegistrySecret;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.createDomainAndVerify;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.createSecretWithUsernamePassword;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.installAndVerifyOperator;
import static org.awaitility.Awaitility.with;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Test to create model in image domain and verify the domain started successfully
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Test to patch the model-in-image image to change WebLogic admin credentials secret")
@IntegrationTest
class ItMiiChangeAdminCredentials implements LoggedTest {

  private static String domainNamespace = null;
  private static String domainUid = "domain1";
  private static ConditionFactory withStandardRetryPolicy = null;

  // admin/managed server name here should match with model yaml in WDT_MODEL_FILE
  private static String adminServerPodName = domainUid + ADMIN_SERVER_NAME_BASE;
  private static String managedServerPrefix = domainUid + MANAGED_SERVER_NAME_BASE;
  private static int replicaCount = 2;

  /**
   * Initialization.
   * @param namespaces list of namespaces created by the IntegrationTestWatcher by the
   JUnit engine parameter resolution mechanism
   */
  @BeforeAll
  public static void initAll(@Namespaces(2) List<String> namespaces) {
    // create standard, reusable retry/backoff policy
    withStandardRetryPolicy = with().pollDelay(2, SECONDS)
        .and().with().pollInterval(10, SECONDS)
        .atMost(6, MINUTES).await();

    // get namespaces 
    assertNotNull(namespaces.get(0), String.format("Namespace %s is null", namespaces.get(0)));
    String opNamespace = namespaces.get(0);

    assertNotNull(namespaces.get(1), String.format("Namespace %s is null", namespaces.get(1)));
    domainNamespace = namespaces.get(1);

    logger.info("Install an operator in namespace {0}, managing namespace {1}",
        opNamespace, domainNamespace); 
    installAndVerifyOperator(opNamespace, domainNamespace);
   
    logger.info("Create model-in-image domain {0} in namespace {1}, and wait until it comes up",
        domainUid, domainNamespace); 
    createAndVerifyMiiDomain();
  }
  
  /**
   * Test patching a running model-in-image domain with a new weblogicCredentialsSecret and then
   * update the domain's restartVersion to trigger a rolling restart of the managed server pods.
   * Verify that the WebLogic server pods are restarted by verifying that each pod's creation time,
   * and the weblogic.domainRestartVersion label are updated.
   */
  @Test
  @DisplayName("Change the WebLogic credentials")
  @Slow
  @MustNotRunInParallel
  public void testChangeWebLogicCredentials() {
    final boolean VALID = true;
    final boolean INVALID = false;

    // get the creation time of the admin server pod before patching
    String adminPodLastCreationTime =
        assertDoesNotThrow(() -> getPodCreationTimestamp(domainNamespace,"",adminServerPodName),
        String.format("Can not find PodCreationTime for pod %s", adminServerPodName));
    assertNotNull(adminPodLastCreationTime, "adminPodCreationTime returns NULL");

    logger.info("Domain {0} in namespace {1}, admin server pod {2} CreationTime before patching is {3}",
        domainUid,
        domainNamespace,
        adminServerPodName,
        adminPodLastCreationTime);
    
    List<String> msLastCreationTime = new ArrayList<String>();
    // get the creation time of the managed server pods before patching
    assertDoesNotThrow(
        () -> { 
          for (int i = 1; i <= replicaCount; i++) {
            String managedServerPodName = managedServerPrefix + i;
            String creationTime = getPodCreationTimestamp(domainNamespace,"", managedServerPodName);
            msLastCreationTime.add(creationTime);

            logger.info("Domain {0} in namespace {1}, managed server pod {2} CreationTime before patching is {3}",
                domainUid,
                domainNamespace,
                managedServerPodName,
                creationTime);
          } 
        },
        String.format("Failed to get PodCreationTime for managed server pods"));
    
    logger.info("Check that before patching current credentials are in valid and new credentials are not");
    verifyCredentials(adminServerPodName, domainNamespace, ADMIN_USERNAME_DEFAULT, ADMIN_PASSWORD_DEFAULT, VALID);
    verifyCredentials(adminServerPodName, domainNamespace, ADMIN_USERNAME_PATCH, ADMIN_PASSWORD_PATCH, INVALID);
    
    // create a new secret for admin credentials
    logger.info("Create a new secret for WebLogic admin credentials");
    String adminSecretName = "weblogic-credentials-new";
    assertDoesNotThrow(() -> createSecretWithUsernamePassword(
        adminSecretName,ADMIN_USERNAME_PATCH,
        ADMIN_PASSWORD_PATCH, domainNamespace),
        String.format("createSecret failed for %s", adminSecretName));

    // patch the domain resource with the new secret and verify that the domain resource is patched.
    logger.info("Patch domain {0} in namespace {1} with the secret {2}, and verify the result",
        domainUid, domainNamespace, adminSecretName); 

    String restartVersion = patchAndVerifyDomainResource(
        domainUid,
        domainNamespace,
        adminServerPodName,
        managedServerPrefix,
        replicaCount,
        adminSecretName);

    logger.info("Wait for domain {0} admin server pod {1} in namespace {2} to be restarted",
        domainUid, adminServerPodName, domainNamespace);
    checkPodRestarted(domainUid, domainNamespace, adminServerPodName, adminPodLastCreationTime);
    
    // check that the admin server pod's label has been updated with the new restarVersion
    checkPodRestartVersionUpdated(adminServerPodName, domainUid, domainNamespace, restartVersion);
    
    // check managed server pods are ready
    for (int i = 1; i <= replicaCount; i++) {
      final String podName = managedServerPrefix + i;
      final String lastCreationTime = msLastCreationTime.get(i - 1);
      logger.info("Wait for managed server pod {0} to be restarted in namespace {1}",
          podName, domainNamespace);
      checkPodRestarted(domainUid, domainNamespace, podName, lastCreationTime);
      
      // check that the managed server pod's label has been updated with the new restarVersion`
      checkPodRestartVersionUpdated(podName, domainUid, domainNamespace, restartVersion);
    }
 
    // check if the new credentials are valid and the old credentials are not valid any more
    logger.info("Check that after patching current credentials are not valid and new credentials are");
    verifyCredentials(adminServerPodName, domainNamespace, ADMIN_USERNAME_DEFAULT, ADMIN_PASSWORD_DEFAULT, INVALID);
    verifyCredentials(adminServerPodName, domainNamespace, ADMIN_USERNAME_PATCH, ADMIN_PASSWORD_PATCH, VALID);
    
    logger.info("Domain {0} in namespace {1} is fully started after changing WebLogic credentials secret",
        domainUid, domainNamespace);
  }

  /**
   * Patch the domain resource with a new WebLogic admin credentials secret.
   * 
   * @param domainResourceName name of the domain resource
   * @param namespace Kubernetes namespace that the domain is hosted
   * @param secretName name of the new WebLogic admin credentials secret
   * @return restartVersion new restartVersion of the domain resource
   */
  private String patchDomainResource(
      String domainResourceName,
      String namespace,
      String secretName
  ) {
    String patch = String.format(
        "[\n  {\"op\": \"replace\", \"path\": \"/spec/%s\", \"value\": \"%s\"}\n]\n",
            "webLogicCredentialsSecret/name", secretName);
    logger.info("About to patch the domain resource {0} in namespace {1} with:{2}\n",
        domainResourceName, namespace, patch);

    assertTrue(patchDomainCustomResource(
            domainResourceName,
            namespace,
            new V1Patch(patch),
            V1Patch.PATCH_FORMAT_JSON_PATCH),
        String.format("Failed to patch the domain resource %s in namespace %s with %s:%s",
            domainResourceName, namespace, "/spec/webLogicCredentialsSecret/name", secretName));

    String restartVersion = assertDoesNotThrow(
        () -> getDomainCustomResource(domainResourceName, namespace).getSpec().getRestartVersion(),
        String.format("Failed to get the restartVersion of %s in namespace %s", domainResourceName, namespace));
    int newVersion = restartVersion == null ? 1 : Integer.valueOf(restartVersion) + 1;
    logger.info("Update restartVersion of domain {0} from {1} to {2}",
        domainResourceName, restartVersion, newVersion);
    patch =
        String.format("[\n  {\"op\": \"replace\", \"path\": \"/spec/restartVersion\", \"value\": \"%s\"}\n]\n",
            newVersion);
    logger.info("About to patch the domain resource {0} in namespace {1} with:{2}\n",
        domainResourceName, namespace, patch);

    assertTrue(patchDomainCustomResource(
            domainResourceName,
            namespace,
            new V1Patch(patch),
            V1Patch.PATCH_FORMAT_JSON_PATCH),
        String.format("Failed to patch the domain resource %s in namespace %s with startVersion:%s",
              domainResourceName, namespace, newVersion));

    String currentVersion = assertDoesNotThrow(
        () -> getDomainCustomResource(domainResourceName, namespace).getSpec().getRestartVersion(),
        String.format("Failed to get the restartVersion of %s in namespace %s", domainResourceName, namespace));
    logger.info("Current restartVersion is %s", currentVersion);
    assertTrue(currentVersion.equals(String.valueOf(newVersion)),
        String.format("Failed to update the restartVersion of domain %s from %s to %s",
            domainResourceName,
            restartVersion,
            newVersion));
    return String.valueOf(newVersion);
  }

  private static Domain createDomainResource(
      String domainUid, 
      String domNamespace, 
      String adminSecretName,
      String repoSecretName, 
      String encryptionSecretName, 
      int replicaCount) {
    // create the domain CR
    return new Domain()
            .apiVersion(DOMAIN_API_VERSION)
            .kind("Domain")
            .metadata(new V1ObjectMeta()
                    .name(domainUid)
                    .namespace(domNamespace))
            .spec(new DomainSpec()
                    .domainUid(domainUid)
                    .domainHomeSourceType("FromModel")
                    .image(MII_BASIC_IMAGE_NAME + ":" + MII_BASIC_IMAGE_TAG)
                    .addImagePullSecretsItem(new V1LocalObjectReference()
                            .name(repoSecretName))
                    .webLogicCredentialsSecret(new V1SecretReference()
                            .name(adminSecretName)
                            .namespace(domNamespace))
                    .includeServerOutInPodLog(true)
                    .serverStartPolicy("IF_NEEDED")
                    .serverPod(new ServerPod()
                            .addEnvItem(new V1EnvVar()
                                    .name("JAVA_OPTIONS")
                                    .value("-Dweblogic.StdoutDebugEnabled=false"))
                            .addEnvItem(new V1EnvVar()
                                    .name("USER_MEM_ARGS")
                                    .value("-Djava.security.egd=file:/dev/./urandom ")))
                    .adminServer(new AdminServer()
                            .serverStartState("RUNNING")
                            .adminService(new AdminService()
                                    .addChannelsItem(new Channel()
                                            .channelName("default")
                                            .nodePort(0))))
                    .addClustersItem(new Cluster()
                            .clusterName("cluster-1")
                            .replicas(replicaCount)
                            .serverStartState("RUNNING"))
                    .configuration(new Configuration()
                            .model(new Model()
                                    .domainType("WLS")
                                    .runtimeEncryptionSecret(encryptionSecretName))
                        .introspectorJobActiveDeadlineSeconds(300L)));

  }

  private String patchAndVerifyDomainResource(
      final String domainUid,
      final String namespace,
      final String adminServerPodName,
      final String managedServerPrefix,
      final int replicaCount,
      final String secretName
  ) {
    logger.info(
        "Patch domain resource {0} in namespace {1} to use the new secret {2}",
        domainUid, namespace, secretName);

    String restartVersion = patchDomainResource(domainUid, namespace, secretName);
    
    logger.info(
        "Check that domain resource {0} in namespace {1} has been patched with new secret {3}",
        domainUid, namespace, secretName);
    checkDomainAdminSecretPatched(domainUid, namespace, secretName);

    // check and wait for the admin server pod to be patched with the new image
    logger.info(
        "Check that admin server pod for domain resource {0} in namespace {1} has been patched with {2}: {3}",
        domainUid, namespace, "/spec/WebLogicCredentialsSecret/name", secretName);

    return restartVersion;
  }

  private void checkDomainAdminSecretPatched(
      String domainUid,
      String namespace,
      String newValue
  ) {
   
    // check if domain resource has been patched with the new secret
    withStandardRetryPolicy
        .conditionEvaluationListener(
            condition -> logger.info("Waiting for domain {0} to be patched in namespace {1} "
            + "(elapsed time {2}ms, remaining time {3}ms)",
            domainUid,
            namespace,
            condition.getElapsedTimeInMS(),
            condition.getRemainingTimeInMS()))
        .until(assertDoesNotThrow(() -> domainResourceAdminSecretPatched(domainUid, namespace, newValue),
            String.format(
               "Domain %s is not patched in namespace %s with admin credentials secret %s",
               domainUid, namespace, newValue)));

  }
  
  private void checkPodRestarted(
      String domainUid,
      String domNamespace,
      String podName,
      String lastCreationTime
  ) {
    withStandardRetryPolicy
        .conditionEvaluationListener(
            condition -> logger.info("Waiting for pod {0} to be restarted in namespace {1} "
            + "(elapsed time {2}ms, remaining time {3}ms)",
            podName,
            domNamespace,
            condition.getElapsedTimeInMS(),
            condition.getRemainingTimeInMS()))
        .until(assertDoesNotThrow(() -> isPodRestarted(podName, domainUid, domNamespace, lastCreationTime),
            String.format(
                "pod %s has not been restarted in namespace %s", podName, domNamespace)));
  }

  private void checkPodRestartVersionUpdated(
      String podName,
      String domainUid,
      String namespace,
      String restartVersion) {
    logger.info("Check if weblogic.domainRestartVersion of pod {0} has been updated", podName);
    boolean restartVersionUpdated = assertDoesNotThrow(
        () -> podRestartVersionUpdated(podName, domainUid, namespace, restartVersion),
        String.format("Failed to get weblogic.domainRestartVersion label of pod %s in namespace %s",
            podName, namespace));
    assertTrue(restartVersionUpdated, 
        String.format("Label weblogic.domainRestartVersion of pod %s in namespace %s has not been updated",
            podName, namespace));
  }
  
  private void verifyCredentials(
      String podName,
      String namespace,
      String username,
      String password,
      boolean shouldBeValid) {
    logger.info("Check if new WebLogic admin credentials are valid");
    boolean connectSucceeded = assertDoesNotThrow(
        () -> credentialsValid(K8S_NODEPORT_HOST, podName, namespace, username, password),
        String.format("Failed to check the credentials on pod %s", podName));
        
    if (shouldBeValid) {
      assertTrue(connectSucceeded, 
          "Given credentials are invalid");
    } else {
      assertFalse(connectSucceeded, 
          "Given credentials are valid when they are not expected to be"); 
    }
  }
  
  private static void createAndVerifyMiiDomain() {
    // Create the repo secret to pull the image
    assertDoesNotThrow(() -> createDockerRegistrySecret(domainNamespace),
            String.format("createSecret failed for %s", REPO_SECRET_NAME));

    // create secret for admin credentials
    logger.info("Create secret for admin credentials");
    String adminSecretName = "weblogic-credentials";
    assertDoesNotThrow(() -> createSecretWithUsernamePassword(
        adminSecretName,
        ADMIN_USERNAME_DEFAULT,
        ADMIN_PASSWORD_DEFAULT,
        domainNamespace),
        String.format("createSecret failed for %s", adminSecretName));

    // create encryption secret
    logger.info("Create encryption secret");
    String encryptionSecretName = "encryptionsecret";
    assertDoesNotThrow(() -> createSecretWithUsernamePassword(
        encryptionSecretName,
        "weblogicenc",
        "weblogicenc",
        domainNamespace),
        String.format("createSecret failed for %s", encryptionSecretName));

    // create the domain CR
    logger.info("Create domain resource {0} object in namespace {1} and verify that it is created",
        domainUid, domainNamespace);
    Domain domain = createDomainResource(domainUid, domainNamespace, adminSecretName, REPO_SECRET_NAME,
        encryptionSecretName, replicaCount);
    createDomainAndVerify(domain, domainNamespace); 

    // check admin server pod is ready
    logger.info("Wait for admin server pod {0} to be ready in namespace {1}",
        adminServerPodName, domainNamespace);
    checkPodReady(adminServerPodName, domainUid, domainNamespace);

    // check managed server pods are ready
    for (int i = 1; i <= replicaCount; i++) {
      logger.info("Wait for managed server pod {0} to be ready in namespace {1}",
          managedServerPrefix + i, domainNamespace);
      checkPodReady(managedServerPrefix + i, domainUid, domainNamespace);
    }

    logger.info("Check admin service {0} is created in namespace {1}",
        adminServerPodName, domainNamespace);
    checkServiceExists(adminServerPodName, domainNamespace);

    // check managed server services created
    for (int i = 1; i <= replicaCount; i++) {
      logger.info("Check managed server service {0} is created in namespace {1}",
          managedServerPrefix + i, domainNamespace);
      checkServiceExists(managedServerPrefix + i, domainNamespace);
    }
  }
}
