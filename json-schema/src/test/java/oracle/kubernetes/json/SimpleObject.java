// Copyright 2018, 2019, Oracle Corporation and/or its affiliates.  All rights reserved.
// Licensed under the Universal Permissive License v 1.0 as shown at
// http://oss.oracle.com/licenses/upl.

package oracle.kubernetes.json;

import javax.annotation.Nonnull;

import com.google.gson.annotations.SerializedName;

@SuppressWarnings("unused")
class SimpleObject {
  private static int staticInt = 2;
  @Description("A flag")
  @Nonnull
  private Boolean aaBoolean = true;
  @Description("A string")
  private String aaString;
  @SerializedName("depth")
  private float aaFloat;
}