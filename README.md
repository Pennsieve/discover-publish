# discover-publish
The Pennsieve Dataset Publishing Workflow

## Multipart Copy/Upload
The Discover Publish task uses the AWS S3 Multipart Upload capability to copy files from the Storage bucket to the Publish bucket. 

There is a test application which employs the same Multipart Upload code which will copy a file from one bucket to another, and return the SHA256 hash in its verbose output. To run this application, use **sbt**:

```
sbt "discover-publish/test:runMain com.pennsieve.publish.MultipartUploaderMain [arguments]"
```
Arguments:
- `--region`
- `--maxPartSize`
- `--sourceBucket`
- `--sourceKey`
- `--destinationBucket`
- `--destinationKey`

