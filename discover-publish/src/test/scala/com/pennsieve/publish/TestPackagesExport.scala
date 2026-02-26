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

import akka.actor.ActorSystem
import com.pennsieve.aws.s3.S3
import com.pennsieve.models.{ Dataset, Organization, Role, User }
import com.pennsieve.test.PersistantTestContainers
import com.pennsieve.test.helpers.TestDatabase
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach, Suite }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import software.amazon.awssdk.services.s3.S3Client

import scala.concurrent.ExecutionContext

class TestPackagesExport
    extends AnyWordSpec
    with Matchers
    with PersistantTestContainers
    with DiscoverPublishS3DockerContainer
    with DiscoverPublishPostgresDockerContainer
    with TestDatabase
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with ValueHelper
    with S3Helper {
  self: Suite =>

  implicit var system: ActorSystem = _
  implicit var executionContext: ExecutionContext = _

  implicit var s3: S3 = _

  val testOrganization: Organization = sampleOrganization

  var testDataset: Dataset = _
  var testUser: User = _
  var publishContainer: PublishContainer = _

  var config: Config = _
  var databaseContainer: InsecureDatabaseContainer = _

  override def afterStart(): Unit = {
    super.afterStart()

    // alpakka-s3 v1.0 can only be configured via Typesafe config passed to the
    // actor system, or as S3Settings that are attached to every graph
    config = ConfigFactory
      .empty()
      .withFallback(postgresContainer.config)
      .withFallback(s3Container.config)
      .withFallback(ConfigFactory.load())

    system = ActorSystem("discover-publish", config)
    executionContext = system.dispatcher

    /*
     * Since PublishContainer is scoped to an organization, and requires a
     * user-actor, use a simple database container to set up initial conditions.
     */
    databaseContainer = InsecureDatabaseContainer(config, testOrganization)
    databaseContainer.db.run(createSchema(testOrganization.id.toString)).await
    migrateOrganizationSchema(
      testOrganization.id,
      databaseContainer.postgresDatabase
    )

    s3 = new S3(s3Container.s3Client)
    val s3Client = s3Container.s3ClientV2
    initS3Helper(s3Client)
  }

  override def afterAll(): Unit = {
    databaseContainer.db.close()
    system.terminate()
    super.afterAll()
  }

  override def beforeEach(): Unit = {
    super.beforeEach()

    s3.createBucket(publishBucket).isRight shouldBe true
    val versioning = enableBucketVersioning(publishBucket)
    versioning.isRight shouldBe true
    s3.createBucket(assetBucket).isRight shouldBe true
    s3.createBucket(sourceBucket).isRight shouldBe true

    testUser = createUser(databaseContainer)
    testDataset = createDatasetWithAssets(
      databaseContainer = databaseContainer,
      s3 = Some(s3)
    )

    publishContainer = {
      PublishContainer(
        config = config,
        s3 = s3,
        s3Client = s3Client,
        s3Bucket = publishBucket,
        s3AssetBucket = assetBucket,
        s3Key = testKeyV5,
        s3AssetKeyPrefix = assetKeyPrefix,
        s3CopyChunkSize = copyChunkSize,
        s3CopyChunkParallelism = copyParallelism,
        s3CopyFileParallelism = copyParallelism,
        doi = testDoi,
        dataset = testDataset,
        publishedDatasetId = 100,
        version = 10,
        organization = testOrganization,
        user = ownerUser,
        userOrcid = "0000-0001-0221-1986",
        datasetRole = Some(Role.Owner),
        contributors = List(contributor),
        collections = List(collection),
        externalPublications = List(externalPublication),
        datasetAssetClient = null,
        workflowId = PublishingWorkflows.Version5,
        expectPrevious = true
      )
    }

    // Simulate discover-s3clean:
    s3PrePublishCleanObjects(publishContainer.s3Bucket, publishContainer.s3Key)
  }

  override def afterEach(): Unit = {
    super.afterEach()
    deleteBucket(publishBucket)
    deleteBucket(assetBucket)
    deleteBucket(sourceBucket)
    publishContainer.db.close()

  }

  "exportPackageSources5x" should {
    "handle swapping names of already published files" in {

      publishContainer =
        publishContainer.copy(version = 2, expectPrevious = true)

      // seed the publish bucket with existing files and the expected manifest:
      val originalFoo =
        uploadPublishedPackage(
          publishContainer.s3Bucket,
          publishContainer.s3Key,
          "foo.txt",
          "foo.txt"
        )
      val originalBar =
        uploadPublishedPackage(
          publishContainer.s3Bucket,
          publishContainer.s3Key,
          "bar.txt",
          "bar.txt"
        )
      val existingManifest = newManifest(
        version = publishContainer.version - 1,
        files = List(originalFoo, originalBar)
      )

      uploadManifest(
        publishContainer.s3Bucket,
        publishContainer.s3Key,
        existingManifest
      )

      val idMap, fileManifests = PackagesExport
        .exportPackageSources5x(publishContainer, existingManifest.files)
        .await
    }
  }
}
