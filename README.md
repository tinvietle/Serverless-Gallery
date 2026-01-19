# Serverless Photo Gallery

A serverless image gallery using **AWS Lambda (Java)**, **S3**, **RDS MySQL**, and **Lambda Function URLs**.

## Architecture

```
┌─────────────┐     ┌─────────────────────────────────────────────────────────┐
│   Frontend  │────▶│                    Lambda Orchestrators                 │
│  index.html │     │  Upload / Download / Delete / List Objects              │
└─────────────┘     └──────────┬──────────────────┬──────────────────┬────────┘
                               │                  │                  │
                    ┌──────────▼──────┐ ┌────────▼────────┐ ┌───────▼───────┐
                    │   S3 Buckets    │ │   RDS MySQL     │ │ Parameter     │
                    │ cloud-public-mpg│ │   (Photos DB)   │ │ Store (Keys)  │
                    │ resized-cloud-  │ └─────────────────┘ └───────────────┘
                    │   public-mpg    │
                    └─────────────────┘
```

## Lambda Functions

| Function | Purpose |
|----------|---------|
| `LambdaUploadOrchestrator` | Orchestrates upload: validates token → uploads original → resizes → uploads thumbnail → stores metadata |
| `LambdaDownloadOrchestrator` | Validates token → returns image as Base64 |
| `LambdaDeleteOrchestrator` | Validates token → deletes from S3 (original + resized) → deletes DB record |
| `LambdaListObjectsOrchestrator` | Validates token → returns photo list from DB |
| `LambdaTokenGenerator` | Generates HMAC-SHA256 token from email + secret key |
| `LambdaTokenChecker` | Validates tokens against Parameter Store secret |
| `LambdaUploadObject` | Uploads Base64 content to specified S3 bucket |
| `LambdaDeleteObject` | Deletes object from specified S3 bucket |
| `LambdaDownloadObject` | Downloads object from S3 as Base64 |
| `LambdaImageResizer` | Resizes image to 100px max dimension |
| `LambdaFetchThumbnails` | Fetches thumbnails from resized bucket |
| `LambdaUploadDescriptionDB` | Inserts photo metadata (S3Key, Description, Email) to RDS |
| `LambdaGetPhotosDB` | Retrieves all photos from RDS |
| `LambdaDeleteDescriptionDB` | Deletes photo record from RDS |

## Frontend (index.html)

**Features:**
- Login with email → generates token via HMAC-SHA256
- Upload photos with description
- View gallery with thumbnails (auto-retry if processing)
- Download full-size images
- Delete photos

**Key Functions:**
- `getTokenForUser()` - Authenticates user
- `triggerUploadFileAndDescription()` - Uploads photo + metadata
- `fetchListOfObjects()` - Loads gallery from DB
- `fetchThumbnail()` - Loads thumbnails with retry logic
- `fetchObject()` / `downloadObject()` - Downloads images
- `deleteObject()` - Removes photos

## Key Patterns

1. **Token Authentication**: HMAC-SHA256 with secret from AWS Parameter Store
2. **Parallel Processing**: Upload + resize run concurrently via `CompletableFuture`
3. **IAM DB Auth**: RDS connections use IAM-generated tokens (no static passwords)
4. **Base64 Encoding**: All binary data transferred as Base64 strings

## Project Structure

```
├── demo/src/main/webapp/index.html    # Frontend application
├── LambdaUploadOrchestrator/          # Main upload workflow
├── LambdaDownloadOrchestrator/        # Authenticated download
├── LambdaDeleteOrchestrator/          # Authenticated delete
├── LambdaListObjectsOrchestrator/     # List photos from DB
├── LambdaTokenGenerator/              # Generate auth tokens
├── LambdaTokenChecker/                # Validate auth tokens
├── LambdaUploadObject/                # S3 upload utility
├── LambdaDeleteObject/                # S3 delete utility
├── LambdaDownloadObject/              # S3 download utility
├── LambdaImageResize/                 # Image resizing (100px)
├── LambdaFetchThumbnails/             # Fetch from resized bucket
├── LambdaUploadDescriptionDB/         # Insert to RDS
├── LambdaGetPhotoDB/                  # Query from RDS
├── LambdaDeleteDescriptionDB/         # Delete from RDS
└── scripts/                           # Deployment scripts
```

## Quick Start

1. **Build**: `cd LambdaXXX && mvn clean package`
2. **Deploy**: Upload JAR to AWS Lambda
3. **Configure**: Set Lambda Function URLs
4. **Update**: Replace URLs in `index.html`

## Acknowledgements

The project is done by myself with great support from my professor Prof. Dr. Manuel Garcia Clavel.
Contact for any questions or suggestions: tinvietle@gmail.com