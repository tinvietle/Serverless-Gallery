# Cloud Computing & DevOps - Final Exam
## Serverless Gallery Project

**Course:** Cloud Computing & DevOps  
**Duration:** 120 minutes  
**Total Points:** 100  
**Date:** January 12, 2026

---

## Instructions

- This is a practical exam where you will fix and improve code for a serverless image gallery application
- Each question provides code with issues that you must identify and correct
- Write your corrected code clearly
- Explain your reasoning for each change
- Partial credit will be awarded for correct explanations even if code is incomplete
- You may refer to AWS documentation
- **Do NOT look at the SOLUTIONS section until you complete the exam**

---

## Question 1: String Comparison Bug (15 points)

### Context
The following Lambda function checks if it was invoked by EventBridge for warming purposes. However, the check never works correctly.

### Code with Issues

```java
package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.json.JSONObject;

public class LambdaGetListOfObjects implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        context.getLogger().log("Received request: " + request.getBody());

        String content = request.getBody();
        if (content == "EventBridgeInvoke") {
            context.getLogger().log("Invoked by EventBridge, no action taken.");
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withBody("No action taken for EventBridge invocation.");
        }

        String bucketName = "cloud-public-mpg";
        // ... rest of code
    }
}
```

### Tasks (15 points)
a) **(5 points)** Identify the bug in the code  
b) **(5 points)** Provide the corrected code  
c) **(5 points)** Explain why the original code doesn't work and why your solution fixes it

---

## Question 2: Hardcoded Configuration Refactoring (20 points)

### Context
The Lambda function below has hardcoded AWS region and bucket names. This makes it difficult to deploy in different environments (dev, staging, production).

### Code with Issues

```java
package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.core.sync.RequestBody;
import java.util.Base64;
import org.json.JSONObject;

public class LambdaUploadObject implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        
        String bucketName = "cloud-public-mpg";
        String requestBody = event.getBody();
        
        JSONObject bodyJSON = new JSONObject(requestBody);
        String content = bodyJSON.getString("content");
        String objName = bodyJSON.getString("key");
        
        byte[] objBytes = Base64.getDecoder().decode(content.getBytes());
        
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(objName)
                .build();

        S3Client s3Client = S3Client.builder()
                .region(Region.US_EAST_1)
                .build();
        s3Client.putObject(putObjectRequest, RequestBody.fromBytes(objBytes));

        String message = "Object uploaded successfully";
        String encodedString = Base64.getEncoder().encodeToString(message.getBytes());

        return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withBody(encodedString)
                .withIsBase64Encoded(true);
    }
}
```

### Tasks (20 points)
a) **(8 points)** Refactor the code to use environment variables for bucket name and region  
b) **(7 points)** Add proper error handling for missing environment variables  
c) **(5 points)** Write the AWS CLI command to set these environment variables when deploying the Lambda

---

## Question 3: RDS Connection Pool Management (20 points)

### Context
The following Lambda creates a new database connection on every invocation, which is inefficient and can exhaust RDS connection limits under high load.

### Code with Issues

```java
package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Properties;
import org.json.JSONObject;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsUtilities;
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest;

public class LambdaUploadDescriptionDB implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final String RDS_INSTANCE_HOSTNAME = "database-1.c6p4im2uqehz.us-east-1.rds.amazonaws.com";
    private static final int RDS_INSTANCE_PORT = 3306;
    private static final String DB_USER = "cloud26";
    private static final String JDBC_URL = "jdbc:mysql://" + RDS_INSTANCE_HOSTNAME + ":" + RDS_INSTANCE_PORT + "/Cloud26";

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        LambdaLogger logger = context.getLogger();

        try {
            JSONObject json = new JSONObject(request.getBody());
            String description = json.getString("description");
            String imageKey = json.getString("imageKey");

            Class.forName("com.mysql.cj.jdbc.Driver");

            // New connection created every time!
            Connection conn = DriverManager.getConnection(JDBC_URL, setMySqlConnectionProperties());
            
            PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO Photos (Description, S3Key) VALUES (?, ?)");
            stmt.setString(1, description);
            stmt.setString(2, imageKey);
            stmt.executeUpdate();
            
            stmt.close();
            conn.close();

            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withBody("{\"message\":\"Success\"}");

        } catch (Exception ex) {
            logger.log("Error: " + ex.getMessage());
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withBody("{\"message\":\"Error\"}");
        }
    }

    private static Properties setMySqlConnectionProperties() throws Exception {
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

        Properties mysqlConnectionProperties = new Properties();
        mysqlConnectionProperties.setProperty("useSSL", "true");
        mysqlConnectionProperties.setProperty("user", DB_USER);
        mysqlConnectionProperties.setProperty("password", authToken);
        return mysqlConnectionProperties;
    }
}
```

### Tasks (20 points)
a) **(10 points)** Refactor to reuse database connections across invocations using static fields  
b) **(5 points)** Add connection validation logic to check if connection is still alive  
c) **(5 points)** Explain the benefits of connection reuse in Lambda and potential pitfalls

---

## Question 4: S3 Event Trigger Configuration (15 points)

### Context
You need to configure an S3 bucket to automatically trigger a Lambda function when images are uploaded.

### Scenario
- **Bucket Name:** `cloud-public-mpg`
- **Lambda Function:** `LambdaResize` (ARN: `arn:aws:lambda:us-east-1:123456789012:function:LambdaResize`)
- **Requirement:** Trigger should only fire for image files (jpg, jpeg, png) in the `uploads/` prefix

### Tasks (15 points)
a) **(7 points)** Write the S3 notification configuration JSON with proper filtering  
b) **(5 points)** Provide the AWS CLI command to add Lambda invoke permission for S3  
c) **(3 points)** Explain what happens if you forget to add the Lambda permission

---

## Question 5: Lambda Orchestration Error Handling (20 points)

### Context
The orchestrator Lambda invokes multiple Lambda functions sequentially but doesn't handle failures properly. If any step fails, the workflow continues and may leave the system in an inconsistent state.

### Code with Issues

```java
package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONObject;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

public class LambdaUploadOrchestrator implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final LambdaClient lambdaClient;

    public LambdaUploadOrchestrator() {
        this.lambdaClient = LambdaClient.builder()
                .region(Region.of("us-east-1"))
                .build();
    }

    public String callLambda(String functionName, String payload, LambdaLogger logger) {
        InvokeRequest invokeRequest = InvokeRequest.builder()
                .functionName(functionName)
                .invocationType("RequestResponse")
                .payload(SdkBytes.fromUtf8String(payload))
                .build();

        InvokeResponse invokeResult = lambdaClient.invoke(invokeRequest);
        ByteBuffer responsePayload = invokeResult.payload().asByteBuffer();
        String responseString = StandardCharsets.UTF_8.decode(responsePayload).toString();
        JSONObject responseObject = new JSONObject(responseString);
        return responseObject.optString("body", "");
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        LambdaLogger logger = context.getLogger();
        
        JSONObject bodyJSON = new JSONObject(event.getBody());
        String content = bodyJSON.getString("content");
        String objName = bodyJSON.getString("key");
        String objDescription = bodyJSON.getString("description");

        String ext = objName.substring(objName.lastIndexOf('.'));
        String uniqueFilename = System.currentTimeMillis() + "_" + UUID.randomUUID().toString() + ext;

        // Step 1: Upload original
        JSONObject filePayload = new JSONObject()
                .put("content", content)
                .put("key", uniqueFilename)
                .put("bucket", "cloud-public-mpg");
        JSONObject fileWrapper = new JSONObject().put("body", filePayload.toString());
        callLambda("LambdaUploadObject", fileWrapper.toString(), logger);

        // Step 2: Resize image
        JSONObject resizePayload = new JSONObject().put("content", content);
        JSONObject resizeWrapper = new JSONObject().put("body", resizePayload.toString());
        String resizeResponse = callLambda("LambdaImageResizer", resizeWrapper.toString(), logger);

        // Step 3: Upload thumbnail
        String resizedKey = "resized-" + uniqueFilename;
        JSONObject resizeImagePayload = new JSONObject()
                .put("content", resizeResponse)
                .put("key", resizedKey)
                .put("bucket", "resized-cloud-public-mpg");
        JSONObject resizeImageWrapper = new JSONObject().put("body", resizeImagePayload.toString());
        callLambda("LambdaUploadObject", resizeImageWrapper.toString(), logger);

        // Step 4: Save metadata
        JSONObject descPayload = new JSONObject()
                .put("imageKey", uniqueFilename)
                .put("description", objDescription);
        JSONObject descWrapper = new JSONObject().put("body", descPayload.toString());
        callLambda("LambdaUploadDescriptionDB", descWrapper.toString(), logger);

        return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withBody("{\"success\":true}");
    }
}
```

### Tasks (20 points)
a) **(10 points)** Add proper error handling to detect Lambda invocation failures and stop the workflow  
b) **(5 points)** Implement rollback logic to clean up if any step fails  
c) **(5 points)** Return appropriate error responses with details about which step failed

---

## Question 6: IAM Policy Creation (10 points)

### Context
You need to create an IAM policy for Lambda functions that interact with S3 and RDS.

### Requirements
- Read and write access to buckets: `cloud-public-mpg` and `resized-cloud-public-mpg`
- List objects in both buckets
- IAM database authentication to RDS instance `db-XYZ123` with user `cloud26`
- Write CloudWatch Logs
- Invoke other Lambda functions

### Tasks (10 points)
a) **(10 points)** Write the complete IAM policy JSON document that grants only the necessary permissions (principle of least privilege)

---

## Bonus Question: Architecture Improvement (10 points)

### Context
The current `LambdaUploadOrchestrator` uses synchronous Lambda invocations, which means:
- Total execution time = sum of all Lambda execution times
- If orchestrator times out, entire workflow fails
- Difficult to retry individual steps

### Task (10 points)
Propose an alternative architecture using AWS Step Functions. Provide:
a) **(5 points)** A text description of the Step Functions state machine workflow  
b) **(5 points)** Explain the advantages of this approach over the current orchestrator Lambda

---

## Submission Guidelines

1. Write your answers clearly for each question
2. Include corrected code snippets
3. Provide explanations where requested
4. For CLI commands, include all necessary parameters
5. Submit within the 120-minute time limit

## Grading Rubric

- **90-100:** Excellent - All issues identified and fixed correctly with clear explanations
- **80-89:** Very Good - Most issues fixed with minor errors
- **70-79:** Good - Major issues fixed but some details missing
- **60-69:** Satisfactory - Partial solutions with understanding of concepts
- **Below 60:** Needs Improvement - Significant gaps in understanding

---

**END OF EXAM**

*Good luck! Remember: Understanding the "why" is as important as the "what".*

---
---
---

# ⚠️ STOP HERE - DO NOT PROCEED TO SOLUTIONS UNTIL EXAM IS COMPLETE ⚠️

---
---
---
