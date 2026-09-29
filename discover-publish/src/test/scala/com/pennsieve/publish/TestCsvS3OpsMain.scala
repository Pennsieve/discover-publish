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

import com.pennsieve.publish.CsvS3OpsMain.{
  buildSingleFileRequest,
  validateSettings,
  S3Operation
}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class TestCsvS3OpsMain extends AnyWordSpec with Matchers {

  "CsvOpsSettings.withSetting" should {
    "parse single-file CLI flags" in {
      val s = CsvOpsSettings()
        .withSetting("--operation", "COPY")
        .withSetting("--source-uri", "s3://src/in.txt")
        .withSetting("--dest-uri", "s3://dst/out.txt")
        .withSetting("--source-version-id", "v123")

      s.operation shouldBe "COPY"
      s.sourceUri shouldBe "s3://src/in.txt"
      s.destinationUri shouldBe "s3://dst/out.txt"
      s.sourceVersionId shouldBe "v123"
      s.isSingleFileMode shouldBe true
    }

    "CLI flags override pre-existing settings" in {
      val base =
        CsvOpsSettings(operation = "DELETE", sourceUri = "s3://env-src/a")
      val s = base
        .withSetting("--operation", "COPY")
        .withSetting("--source-uri", "s3://cli-src/a")

      s.operation shouldBe "COPY"
      s.sourceUri shouldBe "s3://cli-src/a"
    }
  }

  "validateSettings" should {
    "accept CSV-only settings" in {
      val s = CsvOpsSettings(csvFilePath = "ops.csv")
      validateSettings(s) shouldBe Right(())
    }

    "accept single-file settings with operation and source URI" in {
      val s =
        CsvOpsSettings(operation = "DELETE", sourceUri = "s3://bucket/key")
      validateSettings(s) shouldBe Right(())
    }

    "reject when CSV and single-file flags are both provided" in {
      val s = CsvOpsSettings(
        csvFilePath = "ops.csv",
        operation = "COPY",
        sourceUri = "s3://b/k"
      )
      validateSettings(s).isLeft shouldBe true
      validateSettings(s).left
        .exists(_.contains("Cannot combine")) shouldBe true
    }

    "reject when neither CSV nor single-file flags are provided" in {
      validateSettings(CsvOpsSettings()).isLeft shouldBe true
    }

    "reject single-file mode missing --operation" in {
      val s = CsvOpsSettings(sourceUri = "s3://b/k")
      validateSettings(s).left.exists(_.contains("--operation")) shouldBe true
    }

    "reject single-file mode missing --source-uri" in {
      val s = CsvOpsSettings(operation = "COPY")
      validateSettings(s).left.exists(_.contains("--source-uri")) shouldBe true
    }
  }

  "buildSingleFileRequest" should {
    "build a COPY request from valid URIs" in {
      val s = CsvOpsSettings(
        operation = "COPY",
        sourceUri = "s3://src-bucket/path/file.txt",
        destinationUri = "s3://dst-bucket/path/file.txt"
      )
      val req = buildSingleFileRequest(s).toOption.get
      req.operation shouldBe S3Operation.COPY
      req.sourceBucket shouldBe "src-bucket"
      req.sourceKey shouldBe "path/file.txt"
      req.destinationBucket shouldBe Some("dst-bucket")
      req.destinationKey shouldBe Some("path/file.txt")
      req.sourceS3VersionId shouldBe None
    }

    "include source version ID when provided" in {
      val s = CsvOpsSettings(
        operation = "DELETE",
        sourceUri = "s3://bucket/file.txt",
        sourceVersionId = "abc123"
      )
      val req = buildSingleFileRequest(s).toOption.get
      req.sourceS3VersionId shouldBe Some("abc123")
    }

    "reject COPY without --dest-uri" in {
      val s =
        CsvOpsSettings(operation = "COPY", sourceUri = "s3://bucket/file.txt")
      buildSingleFileRequest(s).left
        .exists(_.contains("COPY requires")) shouldBe true
    }

    "reject an invalid source URI" in {
      val s = CsvOpsSettings(operation = "DELETE", sourceUri = "not-an-s3-uri")
      buildSingleFileRequest(s).left
        .exists(_.contains("Invalid --source-uri")) shouldBe true
    }

    "reject an invalid dest URI" in {
      val s = CsvOpsSettings(
        operation = "COPY",
        sourceUri = "s3://src/a",
        destinationUri = "not-an-s3-uri"
      )
      buildSingleFileRequest(s).left
        .exists(_.contains("Invalid --dest-uri")) shouldBe true
    }

    "reject an invalid operation" in {
      val s = CsvOpsSettings(operation = "BOGUS", sourceUri = "s3://src/a")
      buildSingleFileRequest(s).left
        .exists(_.contains("Invalid operation")) shouldBe true
    }
  }
}
