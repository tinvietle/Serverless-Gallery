# Serverless Gallery (Java / AWS Lambda)

This repository contains a set of Java AWS Lambda functions that together implement a simple “serverless gallery” workflow around:

- Uploading images to S3
- Generating thumbnails / resized images
- Listing, downloading, and deleting objects
- Storing and fetching image metadata (description + S3 key) in MySQL on Amazon RDS

It also includes a `demo/` Java webapp (WAR) that uses the AWS SDK.

## Repository layout

- `LambdaEntryPoint/` – API Gateway Lambda that invokes another Lambda (hardcoded) and returns HTML.
- `LambdaGetListOfObjects/` – lists objects in the source bucket and returns JSON.
- `LambdaGetObjects/` – fetches an object by key from the source bucket and returns it as Base64.
- `LambdaDownloadObject/` – fetches an object by key and returns it with a download header.
- `LambdaUploadObject/` – uploads a Base64 payload to a specified S3 bucket/key.
- `LambdaUploadOrchestrator/` – orchestrates upload + resize + upload thumbnail + write metadata.
- `LambdaImageResize/` – resizes an image provided as Base64 (API Gateway request) and returns Base64.
- `LambdaResize/` – resizes images based on S3 events (reads from source bucket, writes to resized bucket).
- `LambdaDeleteObject/` – deletes an object in the source bucket.
- `LambdaDeleteResized/` – deletes the corresponding resized object in the resized bucket based on an S3 event.
- `LambdaUploadDescriptionDB/` – inserts (description, S3Key) into an RDS MySQL table using IAM auth.
- `LambdaGetPhotoDB/` – reads the `Photos` table from RDS MySQL using IAM auth.
- `demo/` – Maven WAR webapp (`demo.war`).

## Prerequisites

- Java:
  - Most Lambda modules compile with Java 21 (`maven.compiler.source/target` = 21).
  - The `demo/` webapp compiles with Java 17.
- Maven 3.x
- AWS resources (not provisioned by this repo):
  - S3 buckets (see “Hardcoded configuration”)
  - Optional: API Gateway triggers for the API-style Lambdas
  - Optional: S3 event triggers for the S3-event Lambdas
  - RDS MySQL instance (see “RDS / DB metadata Lambdas”)

## Building

Each Lambda is its own Maven project and produces a shaded/fat JAR via `maven-shade-plugin`.

Build a single module:

```bash
cd LambdaGetListOfObjects
mvn clean package
```

The output artifact is typically:

- `target/<artifactId>-<version>.jar`

Example (already present in this repo’s targets):

- `LambdaGetListOfObjects/target/LambdaGetListOfObjects-1.0-SNAPSHOT.jar`

Build all Lambda modules:

```bash
for d in Lambda*; do
  if [ -f "$d/pom.xml" ]; then
    (cd "$d" && mvn -q clean package)
  fi
done
```

Build the demo WAR:

```bash
cd demo
mvn clean package
```

## Lambda handlers

All Lambda handlers are Java classes under the package `vgu.cloud26`.

When creating a Lambda in AWS, the handler value is typically:

- `vgu.cloud26.<ClassName>::handleRequest`

Examples:

- `vgu.cloud26.LambdaGetListOfObjects::handleRequest`
- `vgu.cloud26.LambdaUploadObject::handleRequest`
- `vgu.cloud26.LambdaResizer::handleRequest` (S3 event)

## Hardcoded configuration (important)

Several functions currently hardcode region/resources. If your AWS setup differs, update the code accordingly.

### Region

Most functions use `us-east-1` (either `Region.US_EAST_1` or `Region.of("us-east-1")`).

### S3 buckets

Common buckets referenced in code:

- Source bucket: `cloud-public-mpg`
- Resized bucket: `resized-cloud-public-mpg` (or built as `"resized-" + srcBucket`)

The upload orchestrator explicitly writes to:

- `cloud-public-mpg` (original)
- `resized-cloud-public-mpg` (thumbnail)

### API request shapes

Most API Gateway style Lambdas expect JSON in the request body.

Typical examples:

- Get/download object:
  ```json
  {"key": "some-object.png"}
  ```

- Upload object:
  ```json
  {"content": "<base64>", "key": "file.png", "bucket": "cloud-public-mpg"}
  ```

- Orchestrator upload:
  ```json
  {"content": "<base64>", "key": "original-name.png", "description": "..."}
  ```

Most functions return Base64 bodies with `isBase64Encoded=true`.

## RDS / DB metadata Lambdas

Two functions use MySQL on Amazon RDS and IAM DB authentication:

- `LambdaUploadDescriptionDB/` inserts into `Photos(Description, S3Key)`
- `LambdaGetPhotoDB/` selects from `Photos`

Current JDBC configuration is hardcoded in the source (hostname, port, DB name, user).
You will need:

- The RDS instance reachable from Lambda (VPC/subnets + security groups)
- IAM DB authentication enabled
- The Lambda execution role permitted to connect via IAM auth
- A MySQL schema named `Cloud26` and a table `Photos` with columns `ID`, `Description`, `S3Key`

## Demo webapp (Tomcat)

The `demo/` module builds a WAR at `demo/target/demo.war`.

There is a helper script:

- `demo/update_war.sh`

It runs:

```bash
git pull
mvn clean package
sudo cp target/demo.war /opt/tomcat/webapps
```

Note: there is also an `update_war.sh` in `LambdaGetListOfObjects/` with the same contents; it appears to be a copy of the demo script.

## Notes / caveats

- Some modules contain `dependency-reduced-pom.xml` produced by the Shade plugin.
- Some handlers compare strings using `==` (e.g., checking for `"EventBridgeInvoke"`), which will not behave as intended in Java; prefer `.equals(...)` if you rely on that behavior.

