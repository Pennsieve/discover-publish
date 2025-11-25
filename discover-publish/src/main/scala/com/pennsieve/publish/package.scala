/*
 * Copyright 2021 University of Pennsylvania
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pennsieve.publish

import com.amazonaws.services.s3.model.AmazonS3Exception
import org.apache.commons.lang3.StringUtils
import com.pennsieve.models.Utilities._
import software.amazon.awssdk.services.s3.model.NoSuchKeyException

package object utils {

  /**
    * Join two S3 keys, removing intervening whitespace and leading slashes.
    */
  def joinKeys(keyPrefix: String, keySuffix: String): String =
    StringUtils.removeStart(
      StringUtils.appendIfMissing(keyPrefix, "/") +
        StringUtils.removeStart(keySuffix, "/"),
      "/"
    )

  def joinKeys(keys: Seq[String]): String =
    keys.fold("")(joinKeys)

  // we use a combo of SDK v1 and v2, so this is a helper to return true
  // if the given exception indicates that an S3 key does not exist no matter which
  // SDK version is used.
  def isNoSuchKeyError(e: Throwable): Boolean = e match {
    case _: NoSuchKeyException => true
    case s3e: AmazonS3Exception if s3e.getErrorCode == "NoSuchKey" => true
    case _ => false
  }

}
