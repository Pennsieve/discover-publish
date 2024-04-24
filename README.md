# discover-publish
The Pennsieve Dataset Publishing Workflow

## Multipart Copy/Upload

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

