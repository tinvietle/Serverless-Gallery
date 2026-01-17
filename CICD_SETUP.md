# CI/CD Setup for Lambda Functions

This project uses GitHub Actions for automated deployment of Lambda functions to AWS.

## Quick Start

### Prerequisites

1. **AWS CLI** installed and configured locally (for manual deployments)
2. **jq** installed (`sudo apt-get install jq` or `brew install jq`)
3. **GitHub Secrets** configured (for CI/CD):
   - `AWS_ACCESS_KEY_ID`
   - `AWS_SECRET_ACCESS_KEY`

### Setting up GitHub Secrets

1. Go to your repository → **Settings** → **Secrets and variables** → **Actions**
2. Add the following secrets:
   - `AWS_ACCESS_KEY_ID`: Your AWS access key
   - `AWS_SECRET_ACCESS_KEY`: Your AWS secret key

## Configuration

All Lambda configurations are stored in `lambda-config.json`:

```json
{
  "region": "us-east-1",
  "runtime": "java21",
  "timeout": 30,
  "memorySize": 512,
  "lambdas": {
    "LambdaProjectFolder": {
      "functionName": "ActualAWSFunctionName",
      "handler": "package.ClassName::handleRequest",
      "description": "What this lambda does"
    }
  }
}
```

### Adding a New Lambda

1. Create your Lambda project folder (e.g., `LambdaNewFunction/`)
2. Add configuration to `lambda-config.json`:
   ```json
   "LambdaNewFunction": {
     "functionName": "LambdaNewFunction",
     "handler": "vgu.cloud26.LambdaNewFunction::handleRequest",
     "description": "Description of the new function"
   }
   ```
3. Update `.github/workflows/deploy-single-lambda.yml` to add the new option to the dropdown (optional but recommended)

## Deployment Methods

### 1. Automatic Deployment (CI/CD)

Push to the `main` branch automatically triggers deployment for any changed Lambda folders:

```bash
git add LambdaUploadOrchestrator/
git commit -m "Update upload orchestrator"
git push origin main
```

Only the changed Lambda functions will be deployed.

### 2. Manual Deployment via GitHub Actions

1. Go to **Actions** → **Deploy Single Lambda**
2. Click **Run workflow**
3. Select the Lambda to deploy
4. Click **Run workflow**

Or use **Deploy Lambdas** workflow with:
- Leave empty: Deploy all changed lambdas
- `all`: Deploy all lambdas
- `LambdaName`: Deploy specific lambda

### 3. Local Deployment

#### Deploy a single Lambda:
```bash
./scripts/deploy-lambda.sh LambdaUploadOrchestrator
```

#### Deploy all Lambdas:
```bash
./scripts/deploy-all.sh
```

#### Deploy only changed Lambdas (based on git):
```bash
./scripts/deploy-all.sh --changed
```

#### Deploy specific Lambdas:
```bash
./scripts/deploy-all.sh LambdaTokenChecker LambdaTokenGenerator
```

#### List all configured Lambdas:
```bash
./scripts/deploy-all.sh --list
```

## Workflow Files

| File | Purpose |
|------|---------|
| `.github/workflows/deploy-lambdas.yml` | Auto-deploy on push to main |
| `.github/workflows/deploy-single-lambda.yml` | Manual single Lambda deployment |
| `scripts/deploy-lambda.sh` | Local single Lambda deployment |
| `scripts/deploy-all.sh` | Local batch deployment |
| `lambda-config.json` | Lambda configuration |

## Important Notes

1. **Functions must exist**: The deployment scripts only **update** existing Lambda functions. Create them first via AWS Console or Terraform.

2. **IAM Permissions**: The AWS credentials need the following permissions:
   - `lambda:UpdateFunctionCode`
   - `lambda:UpdateFunctionConfiguration`
   - `lambda:GetFunction`

3. **Java 21**: All Lambdas use Java 21 (Corretto). Make sure your AWS Lambda functions are configured with Java 21 runtime.

4. **Build Artifacts**: Maven shade plugin creates uber JARs in `target/` folder.

## Troubleshooting

### "Function does not exist"
Create the function in AWS Console first, then run the deployment.

### "jq: command not found"
Install jq: `sudo apt-get install jq` (Ubuntu) or `brew install jq` (Mac)

### Build fails
Check that you have JDK 21 installed: `java -version`

### Deployment timeout
Increase the timeout in `lambda-config.json` or the AWS Lambda console.
