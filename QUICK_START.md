# Quick Start Guide - Serverless Gallery

This guide will help you quickly understand and deploy the Serverless Gallery system.

## 📖 What is This Project?

A **production-ready serverless image gallery** built with:
- **13 AWS Lambda functions** (Java 21)
- **S3** for image storage (original + thumbnails)
- **RDS MySQL** for metadata
- **API Gateway** for RESTful APIs
- **Event-driven architecture** for automatic thumbnail generation

## 🎯 Key Features

✅ **Upload images** via API with automatic thumbnail generation  
✅ **List all images** in the gallery  
✅ **View images** (original and thumbnail)  
✅ **Store metadata** (descriptions) in MySQL database  
✅ **Delete images** with automatic thumbnail cleanup  
✅ **Event-driven processing** (S3 triggers)  
✅ **IAM database authentication** (no passwords in code)  

## 🚀 Quick Start (5 Minutes)

### Step 1: Prerequisites

```bash
# Check Java version (need 21 for Lambdas, 17 for demo)
java -version

# Check Maven
mvn -version

# Check AWS CLI
aws --version
```

### Step 2: Build All Lambdas

```bash
cd /home/tin/Serverless_Gallery

# Build all at once
for d in Lambda*; do
  if [ -f "$d/pom.xml" ]; then
    echo "Building $d..."
    (cd "$d" && mvn clean package -q)
  fi
done

# Verify JARs were created
find . -name "*-SNAPSHOT.jar" -path "*/target/*"
```

### Step 3: AWS Resources Setup

You'll need to create:

1. **Two S3 Buckets**
   ```bash
   aws s3 mb s3://cloud-public-mpg
   aws s3 mb s3://resized-cloud-public-mpg
   ```

2. **RDS MySQL Instance**
   ```bash
   # Create via AWS Console or CLI
   # Instance: database-1.c6p4im2uqehz.us-east-1.rds.amazonaws.com
   # Enable IAM authentication
   ```

3. **MySQL Database Schema**
   ```sql
   CREATE DATABASE Cloud26;
   USE Cloud26;
   CREATE TABLE Photos (
     ID INT AUTO_INCREMENT PRIMARY KEY,
     Description VARCHAR(500),
     S3Key VARCHAR(255)
   );
   ```

### Step 4: Deploy Lambdas

**Option A: Deploy via AWS CLI**

```bash
# Create IAM role first (see ROLE_POLICY.json)
aws iam create-role --role-name lambda-gallery-role \
  --assume-role-policy-document file://trust-policy.json

aws iam put-role-policy --role-name lambda-gallery-role \
  --policy-name lambda-gallery-policy \
  --policy-document file://permissions.json

# Deploy each Lambda
FUNCTIONS=(
  "LambdaGetListOfObjects"
  "LambdaUploadOrchestrator"
  "LambdaUploadObject"
  "LambdaImageResize"
  "LambdaResize"
  "LambdaGetObjects"
  "LambdaFetchThumbnails"
  "LambdaDeleteObject"
  "LambdaDeleteResized"
  "LambdaUploadDescriptionDB"
  "LambdaGetPhotoDB"
)

for func in "${FUNCTIONS[@]}"; do
  echo "Deploying $func..."
  aws lambda create-function \
    --function-name $func \
    --runtime java21 \
    --handler vgu.cloud26.${func}::handleRequest \
    --role arn:aws:iam::YOUR_ACCOUNT:role/lambda-gallery-role \
    --zip-file fileb://$func/target/$func-1.0-SNAPSHOT.jar \
    --timeout 30 \
    --memory-size 512
done
```

**Option B: Deploy via AWS Console**

1. Go to AWS Lambda Console
2. Create function → Author from scratch
3. Upload JAR from `target/` directory
4. Set handler: `vgu.cloud26.<ClassName>::handleRequest`
5. Configure timeout (30s) and memory (512MB)
6. Attach IAM role with S3, RDS, and Lambda permissions

### Step 5: Configure S3 Triggers

```bash
# Allow S3 to invoke Lambda
aws lambda add-permission \
  --function-name LambdaResize \
  --statement-id s3-trigger \
  --action lambda:InvokeFunction \
  --principal s3.amazonaws.com \
  --source-arn arn:aws:s3:::cloud-public-mpg

# Add S3 event notification
aws s3api put-bucket-notification-configuration \
  --bucket cloud-public-mpg \
  --notification-configuration file://s3-notifications.json
```

**s3-notifications.json:**
```json
{
  "LambdaFunctionConfigurations": [
    {
      "LambdaFunctionArn": "arn:aws:lambda:us-east-1:ACCOUNT:function:LambdaResize",
      "Events": ["s3:ObjectCreated:*"]
    },
    {
      "LambdaFunctionArn": "arn:aws:lambda:us-east-1:ACCOUNT:function:LambdaDeleteResized",
      "Events": ["s3:ObjectRemoved:*"]
    }
  ]
}
```

### Step 6: Create API Gateway (Optional)

1. **Create REST API** in AWS Console
2. **Create resources**: `/upload`, `/list`, `/get`, `/delete`, `/photos`, `/thumbnails`
3. **Add POST methods** for each resource
4. **Configure Lambda integration** (Lambda Proxy)
5. **Enable CORS**
6. **Deploy API** to stage (e.g., "prod")

## 🧪 Test Your Deployment

### Test 1: List Objects (Empty)

```bash
aws lambda invoke \
  --function-name LambdaGetListOfObjects \
  --payload '{}' \
  output.json
cat output.json
```

### Test 2: Upload an Image

```bash
# Encode an image
BASE64=$(base64 -w 0 test-image.jpg)

# Create payload
cat > upload-payload.json << EOF
{
  "body": "{\"content\":\"$BASE64\",\"key\":\"test.jpg\",\"description\":\"Test image\"}"
}
EOF

# Invoke orchestrator
aws lambda invoke \
  --function-name LambdaUploadOrchestrator \
  --payload file://upload-payload.json \
  upload-output.json
```

### Test 3: List Objects (Should show uploaded image)

```bash
aws lambda invoke \
  --function-name LambdaGetListOfObjects \
  --payload '{}' \
  list-output.json
cat list-output.json
```

### Test 4: Get Photo Metadata

```bash
aws lambda invoke \
  --function-name LambdaGetPhotoDB \
  --payload '{}' \
  photos-output.json
cat photos-output.json | jq
```

### Test 5: Verify S3 Buckets

```bash
# Check source bucket
aws s3 ls s3://cloud-public-mpg/

# Check resized bucket
aws s3 ls s3://resized-cloud-public-mpg/
```

## 📁 Project Structure Overview

```
Serverless_Gallery/
├── README.md                       ← Full documentation
├── ARCHITECTURE.md                 ← Detailed architecture diagrams
├── QUICK_START.md                  ← This file
│
├── LambdaUploadOrchestrator/       ← ⭐ Main upload workflow
├── LambdaImageResize/              ← API-based image resizer
├── LambdaResize/                   ← S3 event-triggered resizer
├── LambdaUploadObject/             ← Upload to S3
├── LambdaGetListOfObjects/         ← List S3 objects
├── LambdaGetObjects/               ← Get single object
├── LambdaFetchThumbnails/          ← Get thumbnail
├── LambdaDeleteObject/             ← Delete from source bucket
├── LambdaDeleteResized/            ← Delete from resized bucket
├── LambdaUploadDescriptionDB/      ← Insert to MySQL
├── LambdaGetPhotoDB/               ← Query MySQL
├── LambdaEntryPoint/               ← Demo Lambda-to-Lambda call
│
└── demo/                           ← Tomcat WAR demo app
```

## 🔧 Configuration Checklist

Before running, verify these are configured:

- [ ] S3 buckets created: `cloud-public-mpg` and `resized-cloud-public-mpg`
- [ ] RDS MySQL instance running with IAM auth enabled
- [ ] Database `Cloud26` and table `Photos` created
- [ ] Lambda execution role has S3, RDS, Lambda invoke permissions
- [ ] Lambda functions deployed with correct handlers
- [ ] S3 event triggers configured for LambdaResize and LambdaDeleteResized
- [ ] VPC configuration for RDS-accessing Lambdas
- [ ] Security groups allow MySQL port 3306 from Lambda SG
- [ ] (Optional) API Gateway created and deployed

## 📊 Expected Results

After successful deployment:

**Upload Image:**
- Original stored in `s3://cloud-public-mpg/timestamp_uuid.jpg`
- Thumbnail in `s3://resized-cloud-public-mpg/resized-timestamp_uuid.jpg`
- Metadata in MySQL `Photos` table

**S3 Event Trigger:**
- Manual upload to source bucket → automatic thumbnail generation
- Delete from source bucket → automatic thumbnail deletion

**Database:**
```sql
mysql> SELECT * FROM Photos;
+----+-------------------+-------------------------+
| ID | Description       | S3Key                   |
+----+-------------------+-------------------------+
|  1 | Test image        | 1736601234_abc-def.jpg  |
|  2 | Another photo     | 1736601456_ghi-jkl.png  |
+----+-------------------+-------------------------+
```

## 🐛 Troubleshooting

### Lambda returns error

```bash
# Check CloudWatch Logs
aws logs tail /aws/lambda/LambdaUploadOrchestrator --follow

# Common issues:
# - IAM permissions missing
# - VPC configuration incorrect (for RDS Lambdas)
# - Hardcoded bucket names don't match your buckets
```

### S3 events not triggering Lambda

```bash
# Verify Lambda has permission
aws lambda get-policy --function-name LambdaResize

# Check S3 notification configuration
aws s3api get-bucket-notification-configuration --bucket cloud-public-mpg

# Test manually
aws lambda invoke \
  --function-name LambdaResize \
  --payload file://s3-event-sample.json \
  output.json
```

### RDS connection fails

```bash
# Verify Lambda is in same VPC as RDS
aws lambda get-function-configuration --function-name LambdaUploadDescriptionDB

# Check security group rules
aws ec2 describe-security-groups --group-ids sg-xxxxx

# Test IAM auth token generation
aws rds generate-db-auth-token \
  --hostname database-1.c6p4im2uqehz.us-east-1.rds.amazonaws.com \
  --port 3306 \
  --username cloud26
```

## 🔐 Security Best Practices

1. **Never commit AWS credentials** to Git
2. **Use environment variables** instead of hardcoded values
3. **Enable S3 bucket encryption**
4. **Use VPC endpoints** for S3 access from Lambda
5. **Rotate RDS IAM tokens** automatically (already implemented)
6. **Enable CloudTrail** for audit logging
7. **Set up CloudWatch alarms** for errors and throttling

## 📈 Next Steps

Once deployed:

1. **Monitor CloudWatch Metrics**
   - Lambda invocations, errors, duration
   - S3 requests and data transfer
   - RDS connections and query performance

2. **Optimize Performance**
   - Right-size Lambda memory
   - Enable API Gateway caching
   - Use CloudFront for image delivery

3. **Add Features**
   - User authentication (Cognito)
   - Image search by description
   - Multiple thumbnail sizes
   - Batch upload support
   - Image tagging system

4. **Production Readiness**
   - Set up CI/CD pipeline
   - Add integration tests
   - Implement disaster recovery
   - Configure backup policies

## 📚 Documentation

- **README.md**: Complete documentation with all Lambda details
- **ARCHITECTURE.md**: Visual diagrams and architecture explanations
- **QUICK_START.md**: This file - getting started guide

## 🆘 Need Help?

- Check [README.md](README.md) for detailed Lambda function explanations
- Review [ARCHITECTURE.md](ARCHITECTURE.md) for system design details
- Search CloudWatch Logs for error messages
- Verify IAM permissions match requirements in README

## 📝 Useful Commands Reference

```bash
# Build all Lambdas
for d in Lambda*; do (cd "$d" && mvn clean package -q); done

# List all Lambda functions
aws lambda list-functions --query 'Functions[].FunctionName'

# Update Lambda code
aws lambda update-function-code \
  --function-name LambdaUploadOrchestrator \
  --zip-file fileb://LambdaUploadOrchestrator/target/LambdaUploadOrchestrator-1.0-SNAPSHOT.jar

# View Lambda logs
aws logs tail /aws/lambda/FUNCTION_NAME --follow

# Test Lambda
aws lambda invoke --function-name FUNCTION_NAME --payload '{}' out.json

# List S3 objects
aws s3 ls s3://cloud-public-mpg/ --recursive

# Query RDS
mysql -h database-1.c6p4im2uqehz.us-east-1.rds.amazonaws.com \
  -u cloud26 -p --ssl-mode=REQUIRED Cloud26
```

---

**Happy Coding! 🚀**

For questions or issues, please review the full documentation in README.md and ARCHITECTURE.md.
