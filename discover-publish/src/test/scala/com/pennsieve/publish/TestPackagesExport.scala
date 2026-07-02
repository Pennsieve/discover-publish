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
import com.pennsieve.models.FileState.UPLOADED
import com.pennsieve.models.{
  Dataset,
  ExternalId,
  FileManifest,
  NodeCodes,
  Organization,
  Package,
  Role,
  User
}
import com.pennsieve.test.PersistantTestContainers
import com.pennsieve.test.helpers.TestDatabase
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach, Suite }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.OptionValues

import java.util.UUID
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
    with S3Helper
    with OptionValues {
  self: Suite =>

  implicit var system: ActorSystem = _
  implicit var executionContext: ExecutionContext = _

  implicit var s3: S3 = _

  var testOrganization: Organization = _

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
    databaseContainer =
      bootstrapInsecureDatabaseContainer(config, sampleOrganizationId)
    testOrganization = databaseContainer.organization

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

      val fooName = "foo.txt"
      val fooPath = s"files/$fooName"
      val fooKey = utils.joinKeys(publishContainer.s3Key, fooPath)

      val barName = "bar.txt"
      val barPath = s"files/$barName"
      val barKey = utils.joinKeys(publishContainer.s3Key, barPath)

      val pkgANodeId = NodeCodes.generateId(NodeCodes.packageCode)
      val pkgAContent = "I am Package A"

      val pkgBNodeId = NodeCodes.generateId(NodeCodes.packageCode)
      val pkgBContent = "And I am Package B"

      // seed the publish bucket with version 1 files and the expected manifest:
      val v1PkgA =
        uploadPublishedPackage(
          s3Bucket = publishContainer.s3Bucket,
          publishS3Prefix = publishContainer.s3Key,
          name = fooName,
          path = fooPath,
          sourcePackageId = pkgANodeId,
          content = pkgAContent
        )

      val v1PkgB =
        uploadPublishedPackage(
          s3Bucket = publishContainer.s3Bucket,
          publishS3Prefix = publishContainer.s3Key,
          name = barName,
          path = barPath,
          sourcePackageId = pkgBNodeId,
          content = pkgBContent
        )

      val v1Manifest = newManifest(
        version = publishContainer.version - 1,
        files = List(v1PkgA, v1PkgB)
      )

      uploadManifest(
        publishContainer.s3Bucket,
        publishContainer.s3Key,
        v1Manifest
      )

      // set up pennsieve DB with current packages that will become v2
      // the user has renamed Package A to bar and Pacakge B to foo.
      // Now package A is named bar
      val v2PkgA = createPackageInDb(
        databaseContainer = databaseContainer,
        user = testUser,
        name = barName,
        nodeId = pkgANodeId,
        dataset = testDataset
      )

      // renaming the package does not touch the s3 bucket, key, or versionId in the file table, so
      // the file is still pointing at foo.
      val v2PkgAFile = createPublishedFile(
        fileManager = publishContainer.fileManager,
        pkg = v2PkgA,
        name = barName,
        s3Bucket = publishContainer.s3Bucket,
        s3Key = fooKey,
        publishedS3VersionId = v1PkgA.s3VersionId.value,
        size = pkgAContent.length
      )

      // and package B is named foo
      val v2PkgB = createPackageInDb(
        databaseContainer = databaseContainer,
        user = testUser,
        name = fooName,
        nodeId = pkgBNodeId,
        dataset = testDataset
      )

      val v2PkgBFile = createPublishedFile(
        fileManager = publishContainer.fileManager,
        pkg = v2PkgB,
        name = fooName,
        s3Bucket = publishContainer.s3Bucket,
        s3Key = barKey,
        publishedS3VersionId = v1PkgB.s3VersionId.value,
        size = pkgBContent.length
      )

      val (idMap, fileManifests) = PackagesExport
        .exportPackageSources5x(publishContainer, v1Manifest.files)
        .await

      // now package A is named bar and B is named foo
      idMap should contain theSameElementsAs List(
        ExternalId.nodeId(pkgANodeId) -> barPath,
        ExternalId.intId(v2PkgA.id) -> barPath,
        ExternalId.nodeId(pkgBNodeId) -> fooPath,
        ExternalId.intId(v2PkgB.id) -> fooPath
      )

      val (v2FooContent, v2FooObject) =
        downloadContentAndObject(publishContainer.s3Bucket, fooKey)
      val (v2BarContent, v2BarObject) =
        downloadContentAndObject(publishContainer.s3Bucket, barKey)

      v2FooContent shouldBe pkgBContent
      v2BarContent shouldBe pkgAContent

      fileManifests should contain theSameElementsAs List(
        FileManifest(
          name = barName,
          path = barPath,
          size = pkgAContent.length,
          fileType = v2PkgAFile.fileType,
          sourcePackageId = Some(pkgANodeId),
          id = Some(v2PkgAFile.uuid),
          s3VersionId = Some(v2BarObject.versionId()),
          sha256 = Some(v2BarObject.checksumSHA256())
        ),
        FileManifest(
          name = fooName,
          path = fooPath,
          size = pkgBContent.length,
          fileType = v2PkgBFile.fileType,
          sourcePackageId = Some(pkgBNodeId),
          id = Some(v2PkgBFile.uuid),
          s3VersionId = Some(v2FooObject.versionId()),
          sha256 = Some(v2FooObject.checksumSHA256())
        )
      )

    }

    "handle swapping names of already published file and a new file" in {

      publishContainer =
        publishContainer.copy(version = 2, expectPrevious = true)

      val fooName = "foo.txt"
      val fooPath = s"files/$fooName"
      val fooKey = utils.joinKeys(publishContainer.s3Key, fooPath)

      val barName = "bar.txt"
      val barPath = s"files/$barName"
      val barKey = utils.joinKeys(publishContainer.s3Key, barPath)

      val pkgANodeId = NodeCodes.generateId(NodeCodes.packageCode)
      val pkgAContent = "I am Package A"

      val pkgBNodeId = NodeCodes.generateId(NodeCodes.packageCode)
      val pkgBContent = "And I am Package B"

      // seed the publish bucket with the version 1 file and the expected manifest:
      val v1PkgA =
        uploadPublishedPackage(
          s3Bucket = publishContainer.s3Bucket,
          publishS3Prefix = publishContainer.s3Key,
          name = fooName,
          path = fooPath,
          sourcePackageId = pkgANodeId,
          content = pkgAContent
        )

      val v1Manifest = newManifest(
        version = publishContainer.version - 1,
        files = List(v1PkgA)
      )

      uploadManifest(
        publishContainer.s3Bucket,
        publishContainer.s3Key,
        v1Manifest
      )

      // set up pennsieve DB with current packages that will become v2
      // the user has renamed Package A to bar and Pacakge B to foo.
      // Now package A is named bar
      val v2PkgA = createPackageInDb(
        databaseContainer = databaseContainer,
        user = testUser,
        name = barName,
        nodeId = pkgANodeId,
        dataset = testDataset
      )

      // renaming the package does not touch the s3 key in the file table, so
      // the file is still pointing at foo.
      val v2PkgAFile = createPublishedFile(
        fileManager = publishContainer.fileManager,
        pkg = v2PkgA,
        name = barName,
        s3Bucket = publishContainer.s3Bucket,
        s3Key = fooKey,
        publishedS3VersionId = v1PkgA.s3VersionId.value,
        size = pkgAContent.length
      )

      // and package B is named foo
      val pkgB = createPackageInDb(
        databaseContainer = databaseContainer,
        user = testUser,
        name = fooName,
        nodeId = pkgBNodeId,
        dataset = testDataset
      )

      val (pkgBFile, _) = createFileS3V2Optional(
        fileManager = publishContainer.fileManager,
        `package` = pkgB,
        name = pkgB.name,
        s3Bucket = sourceBucket,
        s3Key = s"${UUID.randomUUID()}/${UUID.randomUUID()}",
        size = pkgBContent.length,
        content = pkgBContent,
        uploadedState = Some(UPLOADED),
        s3Client = Some(s3Client)
      )

      val (idMap, fileManifests) = PackagesExport
        .exportPackageSources5x(publishContainer, v1Manifest.files)
        .await

      // now package A is named bar and B is named foo
      idMap should contain theSameElementsAs List(
        ExternalId.nodeId(pkgANodeId) -> barPath,
        ExternalId.intId(v2PkgA.id) -> barPath,
        ExternalId.nodeId(pkgBNodeId) -> fooPath,
        ExternalId.intId(pkgB.id) -> fooPath
      )

      val (v2FooContent, v2FooObject) =
        downloadContentAndObject(publishContainer.s3Bucket, fooKey)
      val (v2BarContent, v2BarObject) =
        downloadContentAndObject(publishContainer.s3Bucket, barKey)

      v2FooContent shouldBe pkgBContent
      v2BarContent shouldBe pkgAContent

      fileManifests should contain theSameElementsAs List(
        FileManifest(
          name = barName,
          path = barPath,
          size = pkgAContent.length,
          fileType = v2PkgAFile.fileType,
          sourcePackageId = Some(pkgANodeId),
          id = Some(v2PkgAFile.uuid),
          s3VersionId = Some(v2BarObject.versionId()),
          sha256 = Some(v2BarObject.checksumSHA256())
        ),
        FileManifest(
          name = fooName,
          path = fooPath,
          size = pkgBContent.length,
          fileType = pkgBFile.fileType,
          sourcePackageId = Some(pkgBNodeId),
          id = Some(pkgBFile.uuid),
          s3VersionId = Some(v2FooObject.versionId()),
          sha256 = Some(v2FooObject.checksumSHA256())
        )
      )

    }

    "handle renaming already published files" in {

      publishContainer =
        publishContainer.copy(version = 2, expectPrevious = true)

      val n = 10

      // upload v1 files and manifest to the publish bucket.
      val (v1FileManifests, v1NodeToContent) = List
        .tabulate(n) { i =>
          val name = UUID.randomUUID().toString
          val path = s"files/$name"
          val nodeId = NodeCodes.generateId(NodeCodes.packageCode)
          val content = s"${i}_${UUID.randomUUID().toString}"
          val v1FileManifest = uploadPublishedPackage(
            s3Bucket = publishContainer.s3Bucket,
            publishS3Prefix = publishContainer.s3Key,
            name = name,
            path = path,
            sourcePackageId = nodeId,
            content = content
          )
          (v1FileManifest, nodeId -> content)
        }
        .unzip

      val v1Manifest = newManifest(
        version = publishContainer.version - 1,
        files = v1FileManifests
      )

      uploadManifest(
        publishContainer.s3Bucket,
        publishContainer.s3Key,
        v1Manifest
      )

      // set up pennsieve DB with current packages that will become v2
      // it's all the same packages, but with different names.
      // and since this is v2, the files table has publish bucket S3 keys instead
      // of storage bucket keys.

      val v2PackageFiles = for {
        v1FileManifest <- v1FileManifests
        v2Package = createPackageInDb(
          databaseContainer = databaseContainer,
          user = testUser,
          name = UUID.randomUUID().toString, // rename package
          nodeId = v1FileManifest.sourcePackageId.value,
          dataset = testDataset
        )
        // v2 of the file for each package will have a new name, but still the same S3 key since re-names don't touch that.
        v2File = createPublishedFile(
          fileManager = publishContainer.fileManager,
          pkg = v2Package,
          name = v2Package.name,
          s3Bucket = publishContainer.s3Bucket,
          s3Key = utils.joinKeys(publishContainer.s3Key, v1FileManifest.path),
          publishedS3VersionId = v1FileManifest.s3VersionId.value,
          size = v1FileManifest.size
        )
      } yield (v2Package, v2File)

      val (idMap, fileManifests) = PackagesExport
        .exportPackageSources5x(publishContainer, v1Manifest.files)
        .await

      def expectedPath(p: Package): String = s"files/${p.name}"

      val expectedIdMapEntries = v2PackageFiles.flatMap {
        case (p, _) =>
          List(
            ExternalId.nodeId(p.nodeId) -> expectedPath(p),
            ExternalId.intId(p.id) -> expectedPath(p)
          )
      }

      val (expectedFileManifests, v2NodeIdToContent) = v2PackageFiles.map {
        case (p, f) =>
          val expectedV2Path = expectedPath(p)

          // download the actual content at the file's s3 key
          val expectedKey =
            utils.joinKeys(publishContainer.s3Key, expectedV2Path)
          val (content, s3Object) =
            downloadContentAndObject(publishContainer.s3Bucket, expectedKey)

          // and build the expected V2 FileManifest
          val v2FileManifest = FileManifest(
            name = p.name,
            path = expectedV2Path,
            size = content.length,
            fileType = f.fileType,
            sourcePackageId = Some(p.nodeId),
            id = Some(f.uuid),
            s3VersionId = Some(s3Object.versionId()),
            sha256 = Some(s3Object.checksumSHA256())
          )

          (v2FileManifest, p.nodeId -> content)
      }.unzip

      idMap should contain theSameElementsAs expectedIdMapEntries

      v2NodeIdToContent should contain theSameElementsAs v1NodeToContent

      fileManifests should contain theSameElementsAs expectedFileManifests
    }

  }
}
