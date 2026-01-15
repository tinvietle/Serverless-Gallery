# Serverless Gallery (Java / AWS Lambda)

This repository implements a **complete serverless image gallery system** using AWS Lambda, S3, RDS MySQL, and API Gateway. The architecture demonstrates modern cloud-native patterns including event-driven processing, Lambda orchestration, and database integration with IAM authentication.

## 🎯 Overview

The system provides:
- **Image Upload & Storage**: Upload images to S3 with automatic thumbnail generation
- **Automatic Image Resizing**: Event-triggered thumbnail creation (100px max dimension)
- **Metadata Management**: Store and retrieve image descriptions in RDS MySQL
- **Image Gallery Operations**: List, view, download, and delete images
- **API Gateway Integration**: RESTful API endpoints for all operations
- **Demo Web Application**: Java WAR application demonstrating AWS SDK usage

## 🏗️ Architecture

### Core Workflow: Image Upload Pipeline

```
User Upload → LambdaUploadOrchestrator
                 ├─► LambdaUploadObject (original → S3 source bucket)
                 ├─► LambdaImageResizer (resize in-memory)
                 ├─► LambdaUploadObject (thumbnail → S3 resized bucket)
                 └─► LambdaUploadDescriptionDB (metadata → RDS MySQL)
```

### Event-Driven Processing

```
S3 Event (new object) → LambdaResize → Creates thumbnail in resized bucket
S3 Event (delete) → LambdaDeleteResized → Removes corresponding thumbnail
```


## 📦 Lambda Functions Detailed

### 🚀 API Gateway Lambda Functions (Synchronous)

#### 1. **LambdaEntryPoint** 
- **Purpose**: Demo entry point that invokes another Lambda and returns HTML
- **Handler**: `vgu.cloud26.LambdaEntryPoint::handleRequest`
- **Functionality**:
  - Hardcoded to call `BlsLambdaGetObjects` Lambda
  - Passes `{"key": "cloud-public.html"}` as payload
  - Returns HTML content with Base64 encoding
  - Demonstrates Lambda-to-Lambda invocation pattern
- **Use Case**: Sample HTML page serving via Lambda chaining

#### 2. **LambdaGetListOfObjects**
- **Purpose**: List all objects in the source S3 bucket
- **Handler**: `vgu.cloud26.LambdaGetListOfObjects::handleRequest`
- **Functionality**:
  - Connects to `cloud-public-mpg` bucket
  - Lists all S3 objects with their keys and sizes (in KB)
  - Returns JSON array: `[{"key": "...", "size": 123}, ...]`
  - Ignores EventBridge invocations (used for warming)
- **Response Format**: JSON array of objects
- **Use Case**: Gallery index/listing page

#### 3. **LambdaGetObjects**
- **Purpose**: Fetch and return a specific object from S3
- **Handler**: `vgu.cloud26.LambdaGetObject::handleRequest`
- **Request Body**:
  ```json
  {"key": "image.png"}
  ```
- **Functionality**:
  - Validates object exists and size < 10MB
  - Reads object from `cloud-public-mpg` bucket
  - Returns Base64-encoded content
  - Auto-detects MIME type (png → image/png, html → text/html)
- **Response**: Base64-encoded object with appropriate Content-Type
- **Use Case**: Display images or files in browser

#### 4. **LambdaDownloadObject**
- **Purpose**: Download an object with appropriate headers
- **Handler**: `vgu.cloud26.LambdaDownloadObject::handleRequest`
- **Request Body**:
  ```json
  {"key": "photo.jpg"}
  ```
- **Functionality**:
  - Similar to LambdaGetObjects but with download disposition
  - Returns object as downloadable file
- **Use Case**: Force browser download instead of inline display

#### 5. **LambdaUploadObject**
- **Purpose**: Upload Base64 content to S3
- **Handler**: `vgu.cloud26.LambdaUploadObject::handleRequest`
- **Request Body**:
  ```json
  {
    "content": "<base64-encoded-data>",
    "key": "filename.png",
    "bucket": "cloud-public-mpg"
  }
  ```
- **Functionality**:
  - Decodes Base64 content
  - Uploads to specified S3 bucket and key
  - Returns success message
- **Use Case**: Single file upload utility (used by orchestrator)

#### 6. **LambdaUploadOrchestrator** ⭐ (Main Upload Workflow)
- **Purpose**: Orchestrate complete upload workflow
- **Handler**: `vgu.cloud26.LambdaUploadOrchestrator::handleRequest`
- **Request Body**:
  ```json
  {
    "content": "<base64-image>",
    "key": "original-name.jpg",
    "description": "My vacation photo"
  }
  ```
- **Workflow Steps**:
  1. **Generate unique filename**: `timestamp_uuid.ext`
  2. **Upload original**: Call LambdaUploadObject → `cloud-public-mpg/unique-file.jpg`
  3. **Resize image**: Call LambdaImageResizer → returns Base64 thumbnail
  4. **Upload thumbnail**: Call LambdaUploadObject → `resized-cloud-public-mpg/resized-unique-file.jpg`
  5. **Store metadata**: Call LambdaUploadDescriptionDB → save to MySQL
- **Features**:
  - Automatic filename uniqueness (timestamp + UUID)
  - Synchronous orchestration of 4 Lambda functions
  - Comprehensive error logging
- **Use Case**: Primary upload endpoint for gallery

#### 7. **LambdaImageResize**
- **Purpose**: Resize image provided as Base64 (API-style)
- **Handler**: `vgu.cloud26.LambdaImageResizer::handleRequest`
- **Request Body**:
  ```json
  {"content": "<base64-image>"}
  ```
- **Functionality**:
  - Accepts Base64-encoded image
  - Resizes to 100px max dimension (maintains aspect ratio)
  - Uses Java BufferedImage with high-quality rendering
  - Returns Base64-encoded JPEG thumbnail
- **Image Processing**:
  - Maximum dimension: 100px
  - Proportional scaling (no stretching)
  - High-quality interpolation (bicubic)
  - Output format: JPEG
- **Use Case**: On-demand resizing for uploaded images

#### 8. **LambdaDeleteObject**
- **Purpose**: Delete object from source bucket
- **Handler**: `vgu.cloud26.LambdaDeleteObject::handleRequest`
- **Request Body**:
  ```json
  {"key": "file-to-delete.jpg"}
  ```
- **Functionality**:
  - Deletes object from `cloud-public-mpg` bucket
  - Returns success message
  - Logs deletion
- **Note**: Does NOT delete thumbnail (use S3 event trigger for that)
- **Use Case**: Manual object deletion via API

#### 9. **LambdaFetchThumbnails**
- **Purpose**: Fetch thumbnail from resized bucket
- **Handler**: `vgu.cloud26.LambdaFetchThumbnails::handleRequest`
- **Request Body**:
  ```json
  {"key": "resized-filename.jpg"}
  ```
- **Functionality**:
  - Fetches from `resized-cloud-public-mpg` bucket
  - Validates size < 10MB
  - Returns 404 if not found, 413 if too large
  - Returns Base64-encoded thumbnail
- **Error Handling**:
  - 404: Thumbnail not found
  - 413: File too large
  - 200: Success with Base64 content
- **Use Case**: Gallery thumbnail display

### 🎣 S3 Event-Triggered Lambda Functions (Asynchronous)

#### 10. **LambdaResize**
- **Purpose**: Automatically resize images on S3 upload
- **Handler**: `vgu.cloud26.LambdaResizer::handleRequest`
- **Trigger**: S3 Event (ObjectCreated) on `cloud-public-mpg` bucket
- **Functionality**:
  - Triggered when new object uploaded to source bucket
  - Downloads image from S3
  - Validates image type (JPG, JPEG, PNG only)
  - Resizes to 100px max dimension
  - Uploads to `resized-cloud-public-mpg` with prefix "resized-"
- **Event Flow**:
  ```
  Upload to cloud-public-mpg/photo.jpg
    → S3 Event → LambdaResize
    → Creates resized-cloud-public-mpg/resized-photo.jpg
  ```
- **Image Processing**: Same as LambdaImageResizer but reads from S3
- **Use Case**: Automatic thumbnail generation

#### 11. **LambdaDeleteResized**
- **Purpose**: Delete thumbnail when original is deleted
- **Handler**: `vgu.cloud26.LambdaDeleteResized::handleRequest`
- **Trigger**: S3 Event (ObjectRemoved) on `cloud-public-mpg` bucket
- **Functionality**:
  - Triggered when object deleted from source bucket
  - Automatically deletes corresponding resized image
  - Maintains bucket synchronization
- **Event Flow**:
  ```
  Delete cloud-public-mpg/photo.jpg
    → S3 Event → LambdaDeleteResized
    → Deletes resized-cloud-public-mpg/resized-photo.jpg
  ```
- **Use Case**: Automatic cleanup of thumbnails

### 🗄️ Database Lambda Functions (RDS MySQL)

#### 12. **LambdaUploadDescriptionDB**
- **Purpose**: Store image metadata in MySQL
- **Handler**: `vgu.cloud26.LambdaUploadDescriptionDB::handleRequest`
- **Request Body**:
  ```json
  {
    "imageKey": "1234567890_uuid.jpg",
    "description": "Beautiful sunset"
  }
  ```
- **Database**:
  - Host: `database-1.c6p4im2uqehz.us-east-1.rds.amazonaws.com`
  - Port: 3306
  - Schema: `Cloud26`
  - Table: `Photos (ID, Description, S3Key)`
  - User: `cloud26`
- **Functionality**:
  - Connects via IAM database authentication
  - Inserts record: `INSERT INTO Photos (Description, S3Key) VALUES (?, ?)`
  - Uses SSL connection
  - Returns Base64-encoded success message
- **Security**: Uses AWS IAM authentication tokens (no password in code)
- **Use Case**: Store searchable metadata for images

#### 13. **LambdaGetPhotoDB**
- **Purpose**: Retrieve all photo metadata from MySQL
- **Handler**: `vgu.cloud26.LambdaGetPhotosDB::handleRequest`
- **Functionality**:
  - Connects to same RDS instance
  - Executes: `SELECT * FROM Photos`
  - Returns JSON array of all photos
- **Response Format**:
  ```json
  [
    {"ID": 1, "Description": "...", "S3Key": "..."},
    {"ID": 2, "Description": "...", "S3Key": "..."}
  ]
  ```
- **Use Case**: Gallery metadata listing

## 🔧 Demo Web Application

The `demo/` module is a Java WAR application that demonstrates AWS SDK usage:

- **Type**: Java Servlet WAR (Java 17)
- **Deployment**: Tomcat (`/opt/tomcat/webapps/demo.war`)
- **Functionality**: 
  - Direct S3 interactions using AWS Java SDK
  - Examples with IAM roles and fixed credentials
  - Download objects from S3
- **Update Script**: `demo/update_war.sh` 
  - Pulls latest code
  - Builds with Maven
  - Deploys to Tomcat

## 📁 Repository Structure

```
├── README.md
├── demo/                              # Tomcat WAR demo application
│   ├── pom.xml                        # Java 17, Servlet 5.0
│   ├── update_war.sh                  # Deployment script
│   └── src/main/java/
│       ├── GetObjectFixedBucket.java
│       ├── GetObjectFixedBucketWithRole.java
│       ├── DownloadObjectFromBucket.java
│       └── GetObjectFromBucketWithRole.java
│
├── LambdaEntryPoint/                  # Entry point (HTML via Lambda)
├── LambdaGetListOfObjects/            # List S3 objects
├── LambdaGetObjects/                  # Get object by key (Base64)
├── LambdaDownloadObject/              # Download object
├── LambdaUploadObject/                # Upload Base64 to S3
├── LambdaUploadOrchestrator/          # ⭐ Main upload workflow
├── LambdaImageResize/                 # API-style image resizer
├── LambdaResize/                      # S3 event-triggered resizer
├── LambdaDeleteObject/                # Delete from source bucket
├── LambdaDeleteResized/               # Delete from resized bucket (S3 event)
├── LambdaFetchThumbnails/             # Get thumbnail by key
├── LambdaUploadDescriptionDB/         # Insert metadata to MySQL
├── LambdaGetPhotoDB/                  # Query metadata from MySQL
└── LambdaUploadUniqueFilename/        # (Utility function)

Each Lambda directory contains:
├── pom.xml                            # Maven configuration
├── dependency-reduced-pom.xml         # Generated by maven-shade-plugin
├── src/main/java/vgu/cloud26/         # Source code
└── target/                            # Compiled JAR artifacts
    └── <name>-1.0-SNAPSHOT.jar       # Deployable fat JAR
```

## 🔑 AWS Resources Required

### S3 Buckets
- **`cloud-public-mpg`** (Source bucket)
  - Stores original uploaded images
  - S3 events trigger: LambdaResize (ObjectCreated), LambdaDeleteResized (ObjectRemoved)
  
- **`resized-cloud-public-mpg`** (Thumbnail bucket)
  - Stores 100px thumbnails
  - Auto-populated by resize Lambdas

### RDS MySQL Database
- **Instance**: `database-1.c6p4im2uqehz.us-east-1.rds.amazonaws.com:3306`
- **Schema**: `Cloud26`
- **Table**: `Photos`
  ```sql
  CREATE TABLE Photos (
    ID INT AUTO_INCREMENT PRIMARY KEY,
    Description VARCHAR(500),
    S3Key VARCHAR(255)
  );
  ```
- **Authentication**: IAM DB authentication enabled
- **User**: `cloud26` with IAM permissions

### API Gateway (Optional)
- REST API endpoints for each API-style Lambda
- Request/Response mapping for JSON bodies
- CORS configuration for web clients

### IAM Roles & Permissions

**Lambda Execution Role** requires:
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:PutObject",
        "s3:DeleteObject",
        "s3:ListBucket"
      ],
      "Resource": [
        "arn:aws:s3:::cloud-public-mpg/*",
        "arn:aws:s3:::resized-cloud-public-mpg/*",
        "arn:aws:s3:::cloud-public-mpg",
        "arn:aws:s3:::resized-cloud-public-mpg"
      ]
    },
    {
      "Effect": "Allow",
      "Action": [
        "lambda:InvokeFunction"
      ],
      "Resource": "arn:aws:lambda:us-east-1:*:function:*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "rds-db:connect"
      ],
      "Resource": "arn:aws:rds-db:us-east-1:*:dbuser:*/cloud26"
    },
    {
      "Effect": "Allow",
      "Action": [
        "logs:CreateLogGroup",
        "logs:CreateLogStream",
        "logs:PutLogEvents"
      ],
      "Resource": "arn:aws:logs:*:*:*"
    }
  ]
}
```

### VPC Configuration (for RDS Lambdas)
- Lambdas accessing RDS must be in same VPC
- Security groups must allow MySQL port 3306
- NAT Gateway or VPC endpoints for AWS service access

## 🚀 Getting Started

### Prerequisites

- Java:
  - Most Lambda modules compile with Java 21 (`maven.compiler.source/target` = 21).
  - The `demo/` webapp compiles with Java 17.
- Maven 3.x
- AWS resources (not provisioned by this repo):
  - S3 buckets (see "AWS Resources Required" section above)
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

## 📝 Usage Examples

### Example 1: Upload an Image via API

```bash
# 1. Encode image to Base64
base64_content=$(base64 -w 0 my-photo.jpg)

# 2. Call LambdaUploadOrchestrator via API Gateway
curl -X POST https://your-api-gateway.execute-api.us-east-1.amazonaws.com/prod/upload \
  -H "Content-Type: application/json" \
  -d "{
    \"content\": \"$base64_content\",
    \"key\": \"my-photo.jpg\",
    \"description\": \"My beautiful vacation photo\"
  }"
```

**What happens:**
1. Original image uploaded to `cloud-public-mpg/timestamp_uuid.jpg`
2. Thumbnail created and uploaded to `resized-cloud-public-mpg/resized-timestamp_uuid.jpg`
3. Metadata stored in MySQL `Photos` table
4. Returns Base64-encoded success message

### Example 2: List All Images

```bash
curl -X POST https://your-api-gateway.execute-api.us-east-1.amazonaws.com/prod/list \
  -H "Content-Type: application/json"
```

**Response:**
```json
[
  {"key": "1234567890_abc-def.jpg", "size": 245},
  {"key": "1234567891_ghi-jkl.png", "size": 189}
]
```

### Example 3: Get Image Metadata from Database

```bash
curl -X POST https://your-api-gateway.execute-api.us-east-1.amazonaws.com/prod/photos \
  -H "Content-Type: application/json"
```

**Response:**
```json
[
  {
    "ID": 1,
    "Description": "Beautiful sunset",
    "S3Key": "1234567890_abc-def.jpg"
  },
  {
    "ID": 2,
    "Description": "Mountain landscape",
    "S3Key": "1234567891_ghi-jkl.png"
  }
]
```

### Example 4: Fetch a Thumbnail

```bash
curl -X POST https://your-api-gateway.execute-api.us-east-1.amazonaws.com/prod/thumbnails \
  -H "Content-Type: application/json" \
  -d '{"key": "resized-1234567890_abc-def.jpg"}' \
  --output thumbnail.jpg
```

### Example 5: Delete an Image

```bash
curl -X POST https://your-api-gateway.execute-api.us-east-1.amazonaws.com/prod/delete \
  -H "Content-Type: application/json" \
  -d '{"key": "1234567890_abc-def.jpg"}'
```

**Note:** If you have S3 event triggers configured, deleting from source bucket will automatically trigger `LambdaDeleteResized` to remove the thumbnail.

## 🔍 Technical Details

### Image Resizing Algorithm

Both `LambdaImageResize` and `LambdaResize` use the same algorithm:

```java
MAX_DIMENSION = 100px
scalingFactor = min(100/width, 100/height)
newWidth = scalingFactor * width
newHeight = scalingFactor * height
```

- **Maintains aspect ratio** (no stretching)
- **High-quality rendering** (bicubic interpolation)
- **Supported formats**: JPG, JPEG, PNG
- **Output**: Always JPEG for consistency

### Lambda-to-Lambda Invocation

The `LambdaUploadOrchestrator` demonstrates synchronous Lambda invocation:

```java
InvokeRequest invokeRequest = InvokeRequest.builder()
    .functionName("LambdaUploadObject")
    .invocationType("RequestResponse")  // Synchronous
    .payload(SdkBytes.fromUtf8String(jsonPayload))
    .build();

InvokeResponse response = lambdaClient.invoke(invokeRequest);
```

**Performance Consideration:** 
- Orchestrator waits for each Lambda to complete
- Total time = sum of all invoked Lambda execution times
- Consider Step Functions for complex workflows

### IAM Database Authentication

Database Lambdas use AWS IAM instead of passwords:

```java
// Generate temporary authentication token
RdsUtilities rdsUtilities = RdsUtilities.builder()
    .region(Region.US_EAST_1)
    .credentialsProvider(DefaultCredentialsProvider.create())
    .build();

GenerateAuthenticationTokenRequest authRequest = 
    GenerateAuthenticationTokenRequest.builder()
        .hostname(RDS_INSTANCE_HOSTNAME)
        .port(RDS_INSTANCE_PORT)
        .username(DB_USER)
        .build();

String authToken = rdsUtilities.generateAuthenticationToken(authRequest);

Properties props = new Properties();
props.setProperty("user", DB_USER);
props.setProperty("password", authToken);
props.setProperty("useSSL", "true");

Connection conn = DriverManager.getConnection(JDBC_URL, props);
```

**Benefits:**
- No passwords in code or environment variables
- Automatic token rotation
- Centralized access control via IAM policies

### EventBridge Warming Pattern

Several Lambdas check for `"EventBridgeInvoke"`:

```java
if (requestBody == "EventBridgeInvoke") {
    logger.log("Invoked by EventBridge, no action taken.");
    return new APIGatewayProxyResponseEvent()
        .withStatusCode(200)
        .withBody("No action taken for EventBridge invocation.");
}
```

**Purpose:** Keep Lambda functions warm to avoid cold starts

**Note:** ⚠️ This comparison uses `==` instead of `.equals()`, which won't work correctly in Java. Should be:
```java
if ("EventBridgeInvoke".equals(requestBody)) { ... }
```

## ⚠️ Known Issues & Improvements

### Code Issues

1. **String Comparison Bug**
   - Multiple Lambdas use `requestBody == "EventBridgeInvoke"`
   - Should use `"EventBridgeInvoke".equals(requestBody)`
   - Currently won't detect EventBridge invocations correctly

2. **Hardcoded Configuration**
   - Bucket names, regions, RDS endpoints hardcoded
   - Should use environment variables:
     ```java
     String bucket = System.getenv("SOURCE_BUCKET");
     String rdsHost = System.getenv("RDS_HOSTNAME");
     ```

3. **No Error Handling for Failed Lambda Invocations**
   - Orchestrator doesn't rollback if a step fails
   - Could leave orphaned objects or incomplete metadata

### Architectural Improvements

1. **Use AWS Step Functions** instead of orchestrator Lambda
   - Better error handling and retry logic
   - Visual workflow monitoring
   - Automatic compensation on failure

2. **Async Processing** for uploads
   - Return immediately to user
   - Process resize and DB writes asynchronously
   - Use SQS or SNS for coordination

3. **CloudFormation/CDK for Infrastructure**
   - Automate resource provisioning
   - Parameterize bucket names, regions
   - Define S3 triggers declaratively

4. **API Gateway Request Validation**
   - Validate JSON schema before Lambda invocation
   - Reduce Lambda invocation costs
   - Better error messages

5. **Centralized Logging**
   - Use CloudWatch Logs Insights
   - Structured logging (JSON)
   - Correlation IDs across Lambda invocations

### Security Improvements

1. **Input Validation**
   - Validate file types (magic number check)
   - Limit file sizes
   - Sanitize S3 keys

2. **S3 Bucket Policies**
   - Restrict public access
   - Require SSL/TLS
   - Enable versioning and logging

3. **API Gateway Authorization**
   - Add Cognito or Lambda authorizers
   - Rate limiting and throttling
   - API keys for different access levels

## 🎓 Learning Points

This project demonstrates:

1. **Serverless Architecture Patterns**
   - Function orchestration
   - Event-driven processing
   - Microservices design

2. **AWS Service Integration**
   - Lambda with S3, RDS, API Gateway
   - IAM authentication for databases
   - S3 event notifications

3. **Java on AWS Lambda**
   - Maven shade plugin for fat JARs
   - AWS SDK v2 usage
   - Request/Response models

4. **Image Processing at Scale**
   - Serverless image transformation
   - Automatic thumbnail generation
   - Storage optimization

## 📚 Additional Resources

- [AWS Lambda Java Documentation](https://docs.aws.amazon.com/lambda/latest/dg/lambda-java.html)
- [AWS SDK for Java 2.x](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/home.html)
- [S3 Event Notifications](https://docs.aws.amazon.com/AmazonS3/latest/userguide/NotificationHowTo.html)
- [RDS IAM Authentication](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/UsingWithRDS.IAMDBAuth.html)
- [API Gateway Lambda Integration](https://docs.aws.amazon.com/apigateway/latest/developerguide/set-up-lambda-integrations.html)

## 📄 License

This project is provided as-is for educational purposes.

## Notes / caveats

- Some modules contain `dependency-reduced-pom.xml` produced by the Shade plugin.
- Some handlers compare strings using `==` (e.g., checking for `"EventBridgeInvoke"`), which will not behave as intended in Java; prefer `.equals(...)` if you rely on that behavior.

