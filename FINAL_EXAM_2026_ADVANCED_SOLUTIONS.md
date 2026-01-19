# Cloud Computing Final Exam - Advanced Edition - SOLUTIONS

---

## Part A: Code Forensics & Bug Hunting (30 points)

### Question 1 (8 points) - The Silent Failure

**a) Memory Leak Analysis (3 points)**

**Answer:**
- Each photo generates 1 blob URL per `fetchThumbnail` call
- 50 photos × 10 refreshes = **500 blob URLs leaked**
- Each blob URL holds a reference to the blob data in memory

**Correct cleanup approach:**
```javascript
function fetchThumbnail(key, imgElement) {
    // Revoke previous blob URL if exists
    if (imgElement.src && imgElement.src.startsWith('blob:')) {
        URL.revokeObjectURL(imgElement.src);
    }
    
    const body = { "key": "resized-" + key };
    let url = "https://pdgq4una5vr233h5k3emxttyha0pqfzi.lambda-url.us-east-1.on.aws/";

    fetch(url, { method: 'POST', body: JSON.stringify(body) })
        .then(response => {
            if (!response.ok) { throw new Error('Thumbnail not ready'); }
            return response.blob();
        })
        .then((myBlob) => {
            // Revoke old URL before setting new one
            if (imgElement.src && imgElement.src.startsWith('blob:')) {
                URL.revokeObjectURL(imgElement.src);
            }
            const objectURL = URL.createObjectURL(myBlob);
            imgElement.src = objectURL;
            imgElement.alt = key;
        })
        .catch((error) => { /* ... */ });
}

// Also add cleanup when gallery is refreshed:
function renderListOfObjects(listOfObjects) {
    let objectsTable = document.getElementById("objectsTable");
    
    // Cleanup existing blob URLs before removing elements
    objectsTable.querySelectorAll('img').forEach(img => {
        if (img.src && img.src.startsWith('blob:')) {
            URL.revokeObjectURL(img.src);
        }
    });
    
    while (objectsTable.firstChild) {
        objectsTable.removeChild(objectsTable.lastChild);
    }
    // ... rest of function
}
```

---

**b) Recursive Stack Analysis (3 points)**

**Answer:**

**Why no stack overflow:**
- `setTimeout` is asynchronous - it schedules the callback on the event loop
- The current function completes and returns before the timeout fires
- Each call is a NEW stack frame, not nested within the previous one
- The call stack is empty between each retry

**What gets exhausted:**
1. **Timer/Task Queue**: Browser queues up thousands of pending timeouts
2. **Memory**: Each pending Promise and closure consumes memory
3. **Network connections**: Eventually hits browser connection limits
4. **Browser tab resources**: Can cause tab to become unresponsive

**Timeline:**
```
t=0:    fetchThumbnail() called, fails, schedules retry at t=2000
t=0:    Stack is EMPTY (function returned)
t=2000: fetchThumbnail() called, fails, schedules retry at t=4000
t=2000: Stack is EMPTY
t=4000: fetchThumbnail() called, fails, schedules retry at t=6000
...continues indefinitely
```

---

**c) Race Condition (2 points)**

**Answer:**

**Timeline showing the issue:**
```
t=0:     fetchListOfObjects() #1 called
t=50ms:  fetchListOfObjects() #2 called  
t=100ms: #1 renders table, calls fetchThumbnail("photo.jpg", img1)
t=150ms: #2 renders table, calls fetchThumbnail("photo.jpg", img2)
         ← img1 is now orphaned (removed from DOM by #2's render)
t=200ms: #1's fetchThumbnail completes, updates img1 (no longer in DOM!)
t=250ms: #2's fetchThumbnail completes, updates img2 (visible)
         ← Wasted network request, potential memory leak
```

**Fix using Map to track pending requests:**
```javascript
const pendingThumbnails = new Map(); // Global map

function fetchThumbnail(key, imgElement) {
    const thumbnailKey = "resized-" + key;
    
    // Check if request already pending
    if (pendingThumbnails.has(thumbnailKey)) {
        // Reuse existing promise
        pendingThumbnails.get(thumbnailKey)
            .then(objectURL => { imgElement.src = objectURL; })
            .catch(() => { imgElement.alt = "Failed"; });
        return;
    }
    
    const body = { "key": thumbnailKey };
    const url = "https://pdgq4una5vr233h5k3emxttyha0pqfzi.lambda-url.us-east-1.on.aws/";
    
    const promise = fetch(url, { method: 'POST', body: JSON.stringify(body) })
        .then(response => {
            if (!response.ok) throw new Error('Thumbnail not ready');
            return response.blob();
        })
        .then(myBlob => {
            const objectURL = URL.createObjectURL(myBlob);
            return objectURL;
        })
        .finally(() => {
            pendingThumbnails.delete(thumbnailKey);
        });
    
    pendingThumbnails.set(thumbnailKey, promise);
    
    promise
        .then(objectURL => {
            imgElement.src = objectURL;
            imgElement.alt = key;
        })
        .catch(error => {
            imgElement.alt = "Thumbnail processing...";
            if (key !== "cloud-public.html") {
                setTimeout(() => fetchThumbnail(key, imgElement), 2000);
            }
        });
}
```

---

### Question 2 (10 points) - The Double-Decode Disaster

**a) Exact string at each encoding stage (4 points)**

**Original JSON (with special character "é" = UTF-8 bytes 0xC3 0xA9):**
```json
[{"ID":1,"S3Key":"test.jpg","Description":"Café photo","Email":"user@test.com"}]
```

**Stage 1: LambdaGetPhotosDB encodes to Base64:**
```
Input:  [{"ID":1,"S3Key":"test.jpg","Description":"Café photo","Email":"user@test.com"}]
Output: W3siSUQiOjEsIlMzS2V5IjoidGVzdC5qcGciLCJEZXNjcmlwdGlvbiI6IkNhZsOpIHBob3RvIiwiRW1haWwiOiJ1c2VyQHRlc3QuY29tIn1d
```

**Stage 2: LambdaListObjectsOrchestrator extracts body and encodes again:**
```
Input:  W3siSUQiOjEsIlMzS2V5IjoidGVzdC5qcGciLCJEZXNjcmlwdGlvbiI6IkNhZsOpIHBob3RvIiwiRW1haWwiOiJ1c2VyQHRlc3QuY29tIn1d
Output: VzN6aVNWUWlPakVzSWxNelMyVjVJam9pZEdWemRDNXFjR2NpTENKRVpYTmpjbWx3ZEdsdmJpSTZJa05oWnNPcElIQm9iM1J2SWl3aVJXMWhhV3dpT2lKMWMyVnlRSFJsYzNRdVkyOXRJbjFk
```

**Stage 3: Frontend decodes with atob():**
```
Input:  VzN6aVNWUWlPakVzSWxNelMyVjVJam9pZEdWemRDNXFjR2NpTENKRVpYTmpjbWx3ZEdsdmJpSTZJa05oWnNPcElIQm9iM1J2SWl3aVJXMWhhV3dpT2lKMWMyVnlRSFJsYzNRdVkyOXRJbjFk
Output: W3siSUQiOjEsIlMzS2V5IjoidGVzdC5qcGciLCJEZXNjcmlwdGlvbiI6IkNhZsOpIHBob3RvIiwiRW1haWwiOiJ1c2VyQHRlc3QuY29tIn1d

This is STILL Base64! JSON.parse() will FAIL here!
```

**The bug**: Frontend only decodes once, but data is double-encoded. `JSON.parse()` receives a Base64 string, not JSON.

**Actual working flow** (what the code does): Looking more carefully at `callLambda`:
```java
JSONObject responseObject = new JSONObject(responseString);
message = responseObject.optString("body", "");
```

This extracts the `body` field, which in `LambdaGetPhotosDB` is set to:
```java
response.setBody(encodedResult);  // encodedResult = Base64(JSON)
```

So `message` = Base64-encoded JSON. Then orchestrator does:
```java
String encodedString = Base64.getEncoder().encodeToString(responseString.getBytes());
```

Wait, it encodes `responseString` not `message`. Let me re-trace...

Actually the orchestrator does:
```java
responseString = callLambda("LambdaGetPhotosDB", payload, logger);
// responseString = the body field = Base64(JSON)
String encodedString = Base64.getEncoder().encodeToString(responseString.getBytes());
// encodedString = Base64(Base64(JSON))
```

So YES, it's double-encoded. The frontend `atob()` gives Base64(JSON), then `JSON.parse()` fails unless there's another decode happening.

**Looking at actual frontend code:**
```javascript
const decodedText = atob(text);  // Decodes outer Base64
renderListOfObjects(decodedText);  // Passes Base64(JSON) to render

function renderListOfObjects(listOfObjects) {
    let objectsArray = JSON.parse(listOfObjects);  // Tries to parse Base64 string!
}
```

**This should fail!** Unless the inner encoding is automatically decoded somewhere, or the `body` extraction in Java somehow decodes it.

**Correct answer:** There's likely a bug in the code, OR the `LambdaGetPhotosDB` response body is being decoded by the Lambda runtime before `callLambda` reads it.

---

**b) Error propagation when no body field (3 points)**

**Answer:**

```java
message = responseObject.optString("body", "");  // Returns empty string ""
```

**Trace:**
1. `LambdaGetPhotosDB` returns error: `{"statusCode": 500, "error": "DB connection failed"}`
2. `callLambda` parses response, calls `optString("body", "")` → returns `""`
3. `responseString = ""` (empty string)
4. Orchestrator does `Base64.getEncoder().encodeToString("".getBytes())` → returns `""`
5. Frontend receives empty string, `atob("")` → returns `""`
6. `JSON.parse("")` → **throws SyntaxError: Unexpected end of JSON input**
7. User sees: Nothing in gallery, error in console

**The silent failure**: Error from database is completely lost! User has no idea why list is empty.

---

**c) Cleaner architecture without double-encoding (3 points)**

**Answer:**

**Option 1: Don't encode in child Lambda, only in orchestrator**

Modify `LambdaGetPhotosDB`:
```java
// DON'T Base64 encode the body
response.setBody(items.toString());  // Raw JSON
response.withIsBase64Encoded(false);
```

Modify `LambdaListObjectsOrchestrator`:
```java
// callLambda now returns raw JSON
responseString = callLambda("LambdaGetPhotosDB", payload, logger);
// Only encode at the final response
String encodedString = Base64.getEncoder().encodeToString(responseString.getBytes());
```

**Option 2: Pass through encoding flag**

```java
// In orchestrator, check if child response was already encoded
JSONObject childResponse = new JSONObject(rawResponse);
boolean isEncoded = childResponse.optBoolean("isBase64Encoded", false);
String body = childResponse.getString("body");

if (isEncoded) {
    // Already encoded, pass through
    return new APIGatewayProxyResponseEvent()
        .withBody(body)
        .withIsBase64Encoded(true);
} else {
    // Encode now
    return new APIGatewayProxyResponseEvent()
        .withBody(Base64.getEncoder().encodeToString(body.getBytes()))
        .withIsBase64Encoded(true);
}
```

**Option 3: Use binary response with Lambda Function URLs**

Lambda Function URLs can return raw binary without Base64:
```java
// Set Content-Type header and return raw bytes
response.setHeaders(Map.of("Content-Type", "application/json"));
response.setBody(items.toString());
response.setIsBase64Encoded(false);
```

---

### Question 3 (12 points) - Security Vulnerability Assessment

**a) Token with expiration (4 points)**

**Answer:**

**Problem:** Token = `HMAC(email, secret)` has no expiration. Valid forever.

**Solution:** Include timestamp in the signed data.

```java
public static String generateSecureToken(String email, String key, long expirationMinutes, LambdaLogger logger) {
    try {
        // Include expiration timestamp in the token
        long expirationTime = System.currentTimeMillis() + (expirationMinutes * 60 * 1000);
        String dataToSign = email + "|" + expirationTime;
        
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(
            key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKeySpec);
        byte[] hmacBytes = mac.doFinal(dataToSign.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getEncoder().encodeToString(hmacBytes);
        
        // Token format: expirationTime.signature
        // This allows checker to extract expiration before verifying
        String token = expirationTime + "." + signature;
        
        logger.log("Generated token for " + email + " expiring at " + expirationTime);
        return token;
        
    } catch (Exception e) {
        logger.log("Error generating token: " + e.getMessage());
        return null;
    }
}

// Token validation
public static boolean validateToken(String email, String token, String key, LambdaLogger logger) {
    try {
        String[] parts = token.split("\\.");
        if (parts.length != 2) return false;
        
        long expirationTime = Long.parseLong(parts[0]);
        String providedSignature = parts[1];
        
        // Check expiration FIRST (fail fast)
        if (System.currentTimeMillis() > expirationTime) {
            logger.log("Token expired");
            return false;
        }
        
        // Regenerate signature and compare
        String dataToSign = email + "|" + expirationTime;
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(
            key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKeySpec);
        byte[] hmacBytes = mac.doFinal(dataToSign.getBytes(StandardCharsets.UTF_8));
        String expectedSignature = Base64.getEncoder().encodeToString(hmacBytes);
        
        return MessageDigest.isEqual(
            expectedSignature.getBytes(), 
            providedSignature.getBytes()
        );
        
    } catch (Exception e) {
        logger.log("Token validation error: " + e.getMessage());
        return false;
    }
}
```

---

**b) Email Enumeration (2 points)**

**Answer:**

**Attack method:**
1. Attacker calls `LambdaTokenGenerator` with various emails
2. If email exists → returns a valid token
3. If email doesn't exist → still returns a token (HMAC works with any input!)

Wait, the current system doesn't validate if email exists. Let me reconsider...

**Actual vulnerability:** The token generator doesn't check if email exists in database. Anyone can generate tokens for any email.

**But for the checker:**
- If attacker has a token and tries different emails, they can determine which email the token was generated for
- Response time might differ (timing attack)

**Fix:**
```java
// Return consistent error message and timing
JSONObject responseBody = new JSONObject();
responseBody.put("success", false);
responseBody.put("error", "Authentication failed");  // Generic message

// Add constant-time comparison and artificial delay
Thread.sleep(100);  // Consistent response time
```

---

**c) Parameter Store Access exploitation (3 points)**

**Answer:**

**If attacker gains code execution inside Lambda:**
1. They can call the Parameter Store endpoint: `http://localhost:2773/systemsmanager/parameters/get/?name=S3DownloadKey`
2. Retrieve the secret key used for token generation
3. Generate valid tokens for ANY email address
4. Full system compromise

**AWS security features to limit blast radius:**

1. **Parameter Store resource policy**: Restrict which Lambdas can access the parameter
   ```json
   {
     "Version": "2012-10-17",
     "Statement": [{
       "Effect": "Allow",
       "Principal": {"AWS": "arn:aws:iam::ACCOUNT:role/LambdaTokenRole"},
       "Action": "ssm:GetParameter",
       "Resource": "arn:aws:ssm:us-east-1:ACCOUNT:parameter/S3DownloadKey"
     }]
   }
   ```

2. **Separate IAM roles**: Each Lambda should have its own role with minimal permissions

3. **VPC isolation**: Put sensitive Lambdas in VPC, restrict outbound access

4. **AWS KMS encryption**: Encrypt the parameter with a KMS key that has limited access

5. **AWS Secrets Manager rotation**: Use Secrets Manager with automatic rotation so leaked keys become invalid quickly

---

**d) Thumbnail enumeration attack (3 points)**

**Answer:**

**Malicious script:**
```python
import requests
import itertools
import string
from concurrent.futures import ThreadPoolExecutor

URL = "https://pdgq4una5vr233h5k3emxttyha0pqfzi.lambda-url.us-east-1.on.aws/"

def check_thumbnail(key):
    body = {"key": key}
    response = requests.post(URL, json=body)
    if response.status_code == 200:
        print(f"FOUND: {key}")
        return key
    return None

# Generate possible keys
# Pattern: resized-{timestamp}_{uuid}.{ext}
# Timestamp: 13 digits (milliseconds since epoch)
# UUID: 36 characters (8-4-4-4-12 with dashes)

def generate_keys():
    # Try recent timestamps (last 30 days)
    import time
    now = int(time.time() * 1000)
    day_ms = 86400000
    
    for day in range(30):
        base_ts = now - (day * day_ms)
        # Try every second of that day
        for second in range(0, day_ms, 1000):
            ts = base_ts + second
            # UUID is random - can't enumerate
            # Would need to try 2^122 possibilities per timestamp!
            yield f"resized-{ts}_*.jpg"

# Practical attack - enumerate known timestamp patterns
with ThreadPoolExecutor(max_workers=50) as executor:
    # This won't work due to UUID randomness
    pass
```

**Practical limitation that makes this attack difficult:**

1. **UUID randomness**: Each filename has a UUID (128 bits of randomness). Even knowing the exact timestamp, there are 2^122 possible UUIDs (after removing version/variant bits). Brute-forcing is computationally infeasible.

2. **Calculation:**
   - Photos uploaded over 1 year ≈ 365 × 86,400,000 = 31.5 trillion millisecond timestamps
   - Each timestamp has 2^122 ≈ 5.3 × 10^36 UUID possibilities
   - Total combinations: ~10^50 - universe heat death before you find one

3. **Rate limiting**: AWS Lambda URLs have default throttling, and AWS WAF could block rapid requests

4. **Time-based**: Attacker would need to know approximate upload time to reduce search space

**However**, if attacker can observe the S3 bucket (misconfigured public access) or intercept a valid request, they bypass this entirely.

---

## Part B: Distributed Systems Deep Dive (30 points)

### Question 4 (10 points) - Failure Modes & Recovery

**a) Partial Failure Scenario (2 points)**

**System state after DB failure:**
- ✅ Original image exists in `cloud-public-mpg` bucket
- ✅ Resized thumbnail exists in `resized-cloud-public-mpg` bucket  
- ❌ No record in MySQL database

**User experience:**
- Upload appears to succeed (no error returned due to parallel execution)
- Photo does NOT appear in gallery list (list reads from database)
- S3 storage is consumed but photo is "invisible"
- Orphaned files accumulate over time
- User confused: "Upload succeeded but where's my photo?"

---

**b) Compensation Transaction (4 points)**

**Answer:**
```java
@Override
public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
    LambdaLogger logger = context.getLogger();
    
    // ... parse request body, validate token ...
    
    String uniqueFilename = System.currentTimeMillis() + "_" + UUID.randomUUID().toString() + ext;
    String resizedKey = "resized-" + uniqueFilename;
    
    // Track what we've successfully created for potential rollback
    boolean originalUploaded = false;
    boolean resizedUploaded = false;
    
    try {
        // Step 1 & 2: Upload original and resize in parallel
        CompletableFuture<String> uploadOriginalFuture = callLambdaAsync("LambdaUploadObject", 
            createUploadPayload(content, uniqueFilename, "cloud-public-mpg"), logger);
        CompletableFuture<String> resizeFuture = callLambdaAsync("LambdaImageResizer",
            createResizePayload(content), logger);

        String uploadOriginalResponse = uploadOriginalFuture.get(30, TimeUnit.SECONDS);
        originalUploaded = !uploadOriginalResponse.contains("Error");
        
        String resizeResponse = resizeFuture.get(30, TimeUnit.SECONDS);

        // Step 3 & 4: Upload resized and store in DB in parallel
        CompletableFuture<String> uploadResizedFuture = callLambdaAsync("LambdaUploadObject",
            createUploadPayload(resizeResponse, resizedKey, "resized-cloud-public-mpg"), logger);
        CompletableFuture<String> uploadDescFuture = callLambdaAsync("LambdaUploadDescriptionDB",
            createDescPayload(uniqueFilename, objDescription, email), logger);

        String uploadResizedResponse = uploadResizedFuture.get(30, TimeUnit.SECONDS);
        resizedUploaded = !uploadResizedResponse.contains("Error");
        
        String uploadDescResponse = uploadDescFuture.get(30, TimeUnit.SECONDS);
        
        // Check if DB upload failed
        if (uploadDescResponse.contains("Error") || uploadDescResponse.contains("500")) {
            throw new RuntimeException("Database upload failed: " + uploadDescResponse);
        }

        return successResponse("Upload completed successfully");

    } catch (Exception e) {
        logger.log("Error during upload, initiating compensation: " + e.getMessage());
        
        // COMPENSATION: Rollback uploaded files
        CompletableFuture<Void> compensation = CompletableFuture.runAsync(() -> {
            try {
                if (originalUploaded) {
                    logger.log("Compensating: Deleting original file " + uniqueFilename);
                    callLambda("LambdaDeleteObject", 
                        createDeletePayload(uniqueFilename, "cloud-public-mpg"), logger);
                }
                if (resizedUploaded) {
                    logger.log("Compensating: Deleting resized file " + resizedKey);
                    callLambda("LambdaDeleteObject",
                        createDeletePayload(resizedKey, "resized-cloud-public-mpg"), logger);
                }
            } catch (Exception compEx) {
                logger.log("Compensation failed: " + compEx.getMessage());
                // Log to DLQ or alerting system for manual cleanup
            }
        });
        
        // Wait for compensation to complete
        try {
            compensation.get(10, TimeUnit.SECONDS);
        } catch (Exception compEx) {
            logger.log("Compensation timeout: " + compEx.getMessage());
        }

        return new APIGatewayProxyResponseEvent()
            .withStatusCode(500)
            .withBody("Upload failed. Changes have been rolled back.")
            .withHeaders(Map.of("Content-Type", "text/plain"));
    }
}

private String createDeletePayload(String key, String bucket) {
    JSONObject payload = new JSONObject()
        .put("key", key)
        .put("bucket", bucket);
    return new JSONObject().put("body", payload.toString()).toString();
}
```

---

**c) Idempotency with DynamoDB (4 points)**

**Answer:**

**Current problem:**
- Filename uses `System.currentTimeMillis() + UUID.randomUUID()`
- On retry, DIFFERENT timestamp and UUID → creates duplicate
- User retries 3 times → 3 copies of the same photo

**Idempotency solution using DynamoDB:**

```java
public class LambdaUploadOrchestrator {
    
    private final DynamoDbClient dynamoDb;
    private static final String IDEMPOTENCY_TABLE = "UploadIdempotency";
    
    public LambdaUploadOrchestrator() {
        this.lambdaClient = LambdaClient.builder().region(Region.US_EAST_1).build();
        this.dynamoDb = DynamoDbClient.builder().region(Region.US_EAST_1).build();
    }
    
    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        LambdaLogger logger = context.getLogger();
        
        JSONObject bodyJSON = new JSONObject(event.getBody());
        String content = bodyJSON.getString("content");
        String objName = bodyJSON.getString("key");
        String email = bodyJSON.getString("email");
        
        // Generate idempotency key from content hash + email + original filename
        String idempotencyKey = generateIdempotencyKey(content, email, objName);
        
        // Check if this upload was already processed
        String existingResult = checkIdempotencyTable(idempotencyKey, logger);
        if (existingResult != null) {
            logger.log("Duplicate request detected, returning cached result");
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withBody(existingResult)
                .withHeaders(Map.of("Content-Type", "text/plain"));
        }
        
        // Acquire lock (conditional put with TTL)
        boolean lockAcquired = acquireIdempotencyLock(idempotencyKey, logger);
        if (!lockAcquired) {
            // Another invocation is processing this request
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(409)
                .withBody("Upload in progress, please wait")
                .withHeaders(Map.of("Content-Type", "text/plain"));
        }
        
        try {
            // Generate unique filename ONCE and store it
            String uniqueFilename = System.currentTimeMillis() + "_" + UUID.randomUUID().toString() + ext;
            
            // ... perform upload ...
            
            // Store successful result
            storeIdempotencyResult(idempotencyKey, uniqueFilename, logger);
            
            return successResponse(uniqueFilename);
            
        } catch (Exception e) {
            // Release lock on failure
            releaseIdempotencyLock(idempotencyKey, logger);
            throw e;
        }
    }
    
    private String generateIdempotencyKey(String content, String email, String filename) {
        // Hash the content to create stable key
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String combined = email + "|" + filename + "|" + content.substring(0, Math.min(1000, content.length()));
            byte[] hash = md.digest(combined.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            return email + "|" + filename;
        }
    }
    
    private boolean acquireIdempotencyLock(String key, LambdaLogger logger) {
        long ttl = System.currentTimeMillis() / 1000 + 300; // 5 minute TTL
        
        try {
            dynamoDb.putItem(PutItemRequest.builder()
                .tableName(IDEMPOTENCY_TABLE)
                .item(Map.of(
                    "IdempotencyKey", AttributeValue.builder().s(key).build(),
                    "Status", AttributeValue.builder().s("IN_PROGRESS").build(),
                    "TTL", AttributeValue.builder().n(String.valueOf(ttl)).build()
                ))
                .conditionExpression("attribute_not_exists(IdempotencyKey)")
                .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }
    
    private String checkIdempotencyTable(String key, LambdaLogger logger) {
        try {
            GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(IDEMPOTENCY_TABLE)
                .key(Map.of("IdempotencyKey", AttributeValue.builder().s(key).build()))
                .build());
            
            if (response.hasItem() && "COMPLETED".equals(response.item().get("Status").s())) {
                return response.item().get("Result").s();
            }
        } catch (Exception e) {
            logger.log("Idempotency check failed: " + e.getMessage());
        }
        return null;
    }
}
```

**DynamoDB Table Schema:**
```
Table: UploadIdempotency
- IdempotencyKey (String) - Partition Key
- Status (String) - "IN_PROGRESS" | "COMPLETED" | "FAILED"
- Result (String) - The generated filename
- TTL (Number) - Unix timestamp for automatic cleanup
```

---

### Question 5 (10 points) - Concurrency & Race Conditions

**a) Thread Pool Exhaustion (3 points)**

**Answer:**

**Default ForkJoinPool size:**
- `Runtime.getRuntime().availableProcessors()` 
- AWS Lambda: typically 2 vCPUs for 1769MB+ memory
- So pool size ≈ 2 threads

**With 100 concurrent uploads, each spawning 4 async operations:**
- 100 × 4 = 400 async tasks
- Pool has ~2 threads
- 398 tasks queued waiting

**What happens:**
1. Tasks queue up in ForkJoinPool's work queue
2. Only 2 tasks execute at a time
3. Each Lambda invocation blocks waiting for all 4 of its tasks
4. Severe contention and slow execution
5. Lambda timeout likely before completion
6. Potential deadlock if pool threads are blocked waiting for queued tasks (work-stealing helps but doesn't fully prevent)

**Solution:**
```java
// Use dedicated thread pool with proper size
private static final ExecutorService uploadExecutor = Executors.newFixedThreadPool(10);

public CompletableFuture<String> callLambdaAsync(String functionName, String payload, LambdaLogger logger) {
    return CompletableFuture.supplyAsync(() -> {
        return callLambda(functionName, payload, logger);
    }, uploadExecutor);  // Use dedicated pool
}
```

---

**b) Timeout Cascade (3 points)**

**Answer:**

**Current problem:**
```java
String uploadOriginalResponse = uploadOriginalFuture.get();  // Blocks indefinitely!
```

If `LambdaUploadObject` hangs:
1. Orchestrator Lambda blocks on `.get()`
2. Lambda timeout (default 3s, max 15min) eventually kills the function
3. No graceful error handling
4. User gets generic timeout error

**Fixed version:**
```java
try {
    // Set reasonable timeout for each operation
    String uploadOriginalResponse = uploadOriginalFuture.get(30, TimeUnit.SECONDS);
    String resizeResponse = resizeFuture.get(30, TimeUnit.SECONDS);
    
} catch (TimeoutException e) {
    logger.log("Operation timed out: " + e.getMessage());
    
    // Cancel pending futures
    uploadOriginalFuture.cancel(true);
    resizeFuture.cancel(true);
    
    return new APIGatewayProxyResponseEvent()
        .withStatusCode(504)  // Gateway Timeout
        .withBody("{\"error\": \"Upload operation timed out. Please try again.\"}")
        .withHeaders(Map.of("Content-Type", "application/json"));
        
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    logger.log("Operation interrupted: " + e.getMessage());
    
    return new APIGatewayProxyResponseEvent()
        .withStatusCode(500)
        .withBody("{\"error\": \"Operation was interrupted\"}")
        .withHeaders(Map.of("Content-Type", "application/json"));
        
} catch (ExecutionException e) {
    logger.log("Operation failed: " + e.getCause().getMessage());
    
    return new APIGatewayProxyResponseEvent()
        .withStatusCode(500)
        .withBody("{\"error\": \"" + e.getCause().getMessage() + "\"}")
        .withHeaders(Map.of("Content-Type", "application/json"));
}
```

---

**c) S3 and RDS Consistency Models (4 points)**

**Answer:**

| Operation | Consistency Model | Explanation |
|-----------|------------------|-------------|
| **S3 PUT (new object)** | **Strong consistency** (since Dec 2020) | Immediately visible for GET after PUT returns. A read-after-write will always see the new object. |
| **S3 DELETE** | **Strong consistency** | After DELETE returns, subsequent GET returns 404. No eventual consistency delay. |
| **S3 LIST after PUT** | **Strong consistency** | Object appears in list immediately after PUT. |
| **RDS MySQL read-after-write** | **Strong consistency** (single primary) | Same connection sees its own writes immediately. Other connections see writes after commit. |

**However, there's still a race condition in this system:**

```
Timeline:
t=0:    Upload orchestrator starts
t=100:  S3 PUT completes (original image)
t=150:  S3 PUT completes (resized image)  
t=200:  RDS INSERT starts
t=250:  User clicks "List" button
t=300:  LambdaGetPhotosDB queries RDS - INSERT not committed yet!
t=350:  RDS INSERT commits
t=400:  List response returned to user - MISSING THE NEW PHOTO
```

**The issue:** RDS write might not be committed when user queries. This isn't eventual consistency, it's a timing issue with parallel operations.

**Solution:** Return the new photo's data in the upload response so the frontend can optimistically update the list.

---

### Question 6 (10 points) - Lambda Internals

**a) Cold Start Anatomy for RDS-connected Lambda (4 points)**

**Answer:**

| Step | Action | Estimated Time |
|------|--------|----------------|
| 1 | AWS provisions/unfreezes a container | 100-200ms |
| 2 | Download deployment package from S3 | 50-500ms (depends on JAR size, ~10MB typical) |
| 3 | Start JVM | 200-500ms |
| 4 | Load Java classes (static initializers) | 100-300ms |
| 5 | Execute static block: `LambdaClient.builder().build()` | 200-400ms |
| 6 | Handler called, parse request | 10-20ms |
| 7 | `Class.forName("com.mysql.cj.jdbc.Driver")` | 50-100ms |
| 8 | Generate IAM auth token (API call to RDS) | 100-300ms |
| 9 | Establish TCP connection to RDS | 50-100ms |
| 10 | TLS handshake with RDS | 50-100ms |
| 11 | MySQL authentication with IAM token | 50-100ms |
| 12 | Execute SQL query | 10-50ms |

**Total cold start: 1-3 seconds** (compared to ~50ms warm)

**VPC additional delay** (if Lambda is in VPC):
- ENI attachment: 500ms-10s (mitigated by VPC improvements in 2019)
- Modern VPC-enabled Lambdas: ~500ms additional

---

**b) Connection Pooling Problem (3 points)**

**Answer:**

**Calculation:**
- 1000 requests/second
- 100ms average duration
- Concurrent connections = requests/sec × duration = 1000 × 0.1 = **100 concurrent connections**

**RDS connection limits:**
- db.t3.micro: 66 connections
- db.t3.small: 150 connections
- db.t3.medium: 300 connections
- db.m5.large: 1000 connections

**With 100 concurrent connections:** Works on t3.small or larger, but no headroom.

**Connection reuse across warm invocations:**

```java
public class LambdaUploadDescriptionDB {
    
    // Static connection - survives across invocations in same container
    private static Connection cachedConnection = null;
    private static long connectionCreatedAt = 0;
    private static final long CONNECTION_TTL_MS = 300000; // 5 minutes
    
    private Connection getConnection(LambdaLogger logger) throws Exception {
        long now = System.currentTimeMillis();
        
        // Check if cached connection is valid
        if (cachedConnection != null) {
            try {
                // Check if connection is still alive and not expired
                if (!cachedConnection.isClosed() && 
                    (now - connectionCreatedAt) < CONNECTION_TTL_MS) {
                    
                    // Validate with quick query
                    cachedConnection.createStatement().execute("SELECT 1");
                    logger.log("Reusing cached connection");
                    return cachedConnection;
                }
            } catch (SQLException e) {
                logger.log("Cached connection invalid: " + e.getMessage());
            }
            
            // Close stale connection
            try { cachedConnection.close(); } catch (Exception e) {}
            cachedConnection = null;
        }
        
        // Create new connection
        logger.log("Creating new database connection");
        Class.forName("com.mysql.cj.jdbc.Driver");
        cachedConnection = DriverManager.getConnection(JDBC_URL, setMySqlConnectionProperties());
        connectionCreatedAt = now;
        
        return cachedConnection;
    }
    
    @Override
    public APIGatewayProxyResponseEvent handleRequest(...) {
        try {
            Connection conn = getConnection(logger);
            // Use connection WITHOUT closing it
            PreparedStatement stmt = conn.prepareStatement("INSERT INTO Photos...");
            stmt.executeUpdate();
            stmt.close();  // Close statement, keep connection
            
        } catch (Exception ex) {
            // On error, clear cached connection
            cachedConnection = null;
            // ... handle error
        }
    }
}
```

**Note:** IAM auth tokens expire after 15 minutes, so connection reuse is limited by that.

---

**c) Memory vs. CPU Tradeoff (3 points)**

**Answer:**

**Lambda CPU allocation:**
- CPU scales linearly with memory
- At 1769 MB: 1 full vCPU
- At 512 MB: ~0.29 vCPU (512/1769)
- At 1024 MB: ~0.58 vCPU

**Current: 512MB, 3 seconds**
- CPU-bound task (image resize)
- Using ~0.29 vCPU

**At 1024MB (double memory):**
- CPU doubles to ~0.58 vCPU
- Image processing is CPU-bound
- **Expected time: ~1.5 seconds** (linear speedup)

**Cost calculation:**

| Config | Memory | Time | GB-seconds | Cost |
|--------|--------|------|------------|------|
| Current | 512MB | 3s | 0.5 × 3 = 1.5 GB-s | 1.5 × $0.0000166667 = $0.000025 |
| Upgraded | 1024MB | 1.5s | 1.0 × 1.5 = 1.5 GB-s | 1.5 × $0.0000166667 = $0.000025 |

**Same cost!** For CPU-bound tasks, doubling memory (and CPU) halves execution time, resulting in identical GB-seconds.

**Actually better:** The 1024MB version is preferred because:
1. Same cost
2. Faster user experience
3. Fewer concurrent executions needed
4. Less chance of timeout

**For memory-bound tasks:** Doubling memory without reducing time increases cost.

---

## Part C: Architecture Evolution (35 points)

### Question 7 (12 points) - Event-Driven Redesign

**a) SQS-based upload pipeline (5 points)**

**Answer:**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              UPLOAD FLOW                                     │
└─────────────────────────────────────────────────────────────────────────────┘

┌──────────┐     ┌─────────────┐     ┌──────────────────┐     ┌─────────────┐
│  Client  │────▶│ API Gateway │────▶│ Upload Initiator │────▶│  Staging    │
│          │     │             │     │ Lambda           │     │  S3 Bucket  │
└──────────┘     └─────────────┘     └────────┬─────────┘     └─────────────┘
                                              │
                                              │ Generate Job ID
                                              │ Return immediately
                                              ▼
                                     ┌─────────────────┐
                                     │  SQS Queue      │
                                     │  (Upload Jobs)  │
                                     └────────┬────────┘
                                              │
                                              │ Trigger
                                              ▼
                                     ┌─────────────────┐
                                     │ Worker Lambda   │
                                     │ - Move to prod  │
                                     │ - Resize        │
                                     │ - Store in DB   │
                                     └────────┬────────┘
                                              │
                    ┌─────────────────────────┼─────────────────────────┐
                    │                         │                         │
                    ▼                         ▼                         ▼
           ┌──────────────┐         ┌──────────────┐         ┌──────────────┐
           │ Production   │         │   Resized    │         │   DynamoDB   │
           │ S3 Bucket    │         │  S3 Bucket   │         │ (Job Status) │
           └──────────────┘         └──────────────┘         └──────────────┘
                                              │
                                              │ On Failure
                                              ▼
                                     ┌─────────────────┐
                                     │  Dead Letter    │
                                     │  Queue (DLQ)    │
                                     └────────┬────────┘
                                              │
                                              ▼
                                     ┌─────────────────┐
                                     │ Alert/Cleanup   │
                                     │ Lambda          │
                                     └─────────────────┘
```

**Handling challenges:**

**1. Duplicate messages (at-least-once delivery):**
```java
// In Worker Lambda
public void handleMessage(SQSEvent.SQSMessage message) {
    String jobId = message.getMessageId();
    String messageBody = message.getBody();
    JSONObject job = new JSONObject(messageBody);
    
    // Check if already processed using DynamoDB conditional write
    try {
        dynamoDb.putItem(PutItemRequest.builder()
            .tableName("UploadJobs")
            .item(Map.of(
                "JobId", AttributeValue.builder().s(jobId).build(),
                "Status", AttributeValue.builder().s("PROCESSING").build()
            ))
            .conditionExpression("attribute_not_exists(JobId)")
            .build());
    } catch (ConditionalCheckFailedException e) {
        // Already processed, skip
        logger.log("Duplicate message, skipping: " + jobId);
        return;
    }
    
    // Process the upload...
}
```

**2. Failed processing (DLQ):**
```yaml
# SQS Queue configuration
MainQueue:
  RedrivePolicy:
    deadLetterTargetArn: !GetAtt DLQ.Arn
    maxReceiveCount: 3  # Retry 3 times before DLQ

DLQ:
  # Trigger alert Lambda
  Events:
    - DLQProcessor Lambda
```

**3. User notification of completion:**
```javascript
// Frontend polls for status
async function pollUploadStatus(jobId) {
    const pollInterval = setInterval(async () => {
        const response = await fetch(`/status/${jobId}`);
        const status = await response.json();
        
        if (status.state === 'COMPLETED') {
            clearInterval(pollInterval);
            showSuccess(status.result);
            refreshGallery();
        } else if (status.state === 'FAILED') {
            clearInterval(pollInterval);
            showError(status.error);
        }
        // Continue polling for PENDING/PROCESSING
    }, 2000);
}
```

Alternative: WebSocket for real-time notification:
```
Client ◀──WebSocket──▶ API Gateway ◀──▶ Lambda (sends notification when done)
```

---

**b) Cost comparison (4 points)**

**Answer:**

**Current synchronous design:**
- Orchestrator runs for 5 seconds per upload
- Memory: 512MB
- GB-seconds per upload: 0.5 GB × 5s = 2.5 GB-s
- Daily uploads: 10,000
- Daily GB-seconds: 10,000 × 2.5 = 25,000 GB-s
- Monthly GB-seconds: 25,000 × 30 = 750,000 GB-s
- **Monthly cost: 750,000 × $0.0000166667 = $12.50**

Plus child Lambda costs (simplified):
- 4 child Lambdas × 1s each × 512MB = 2 GB-s per upload
- Monthly: 10,000 × 30 × 2 = 600,000 GB-s = **$10.00**

**Total current: ~$22.50/month**

---

**New event-driven design:**
- Upload Initiator: 200ms × 256MB = 0.05 GB-s
- Worker Lambda: 500ms × 512MB = 0.25 GB-s
- Total per upload: 0.3 GB-s
- Monthly GB-seconds: 10,000 × 30 × 0.3 = 90,000 GB-s
- **Monthly Lambda cost: 90,000 × $0.0000166667 = $1.50**

Additional costs:
- SQS: $0.40 per million requests = ~$0.12/month
- DynamoDB: ~$1/month for status tracking

**Total new: ~$2.62/month**

**Savings: ~88%**

---

**c) Migration strategy (3 points)**

**Answer:**

**Phase 1: Deploy parallel infrastructure**
- Deploy SQS, DynamoDB, new Lambdas
- Keep existing synchronous endpoint active
- Add feature flag in frontend

**Phase 2: Shadow mode**
- Frontend calls BOTH endpoints
- Old endpoint returns response to user
- New endpoint processes in background
- Compare results, log discrepancies

**Phase 3: Gradual rollout**
- 10% of uploads → new async endpoint
- Frontend shows "processing" spinner
- Polls for completion
- Monitor for issues

**Phase 4: Full migration**
- 100% to new endpoint
- Deprecate synchronous endpoint
- Update documentation

**Frontend changes for async:**
```javascript
async function triggerUploadFileAndDescription() {
    // ... existing code to prepare body ...
    
    const response = await fetch(NEW_UPLOAD_URL, {
        method: 'POST',
        body: JSON.stringify(body)
    });
    
    const { jobId } = await response.json();
    
    // Show processing state
    showUploadProgress(jobId);
    
    // Poll for completion
    pollUploadStatus(jobId);
}
```

---

### Question 8 (12 points) - Multi-Tenant Evolution

**a) Multi-tenant database schema (4 points)**

**Answer:**

```sql
-- Organizations table
CREATE TABLE Organizations (
    OrgID INT AUTO_INCREMENT PRIMARY KEY,
    OrgName VARCHAR(100) NOT NULL,
    OrgSlug VARCHAR(50) UNIQUE NOT NULL,
    CreatedAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    StorageQuotaBytes BIGINT DEFAULT 10737418240,  -- 10GB default
    IsActive BOOLEAN DEFAULT TRUE
);

-- Users table
CREATE TABLE Users (
    UserID INT AUTO_INCREMENT PRIMARY KEY,
    Email VARCHAR(255) UNIQUE NOT NULL,
    PasswordHash VARCHAR(255),  -- If not using external auth
    OrgID INT NOT NULL,
    Role ENUM('admin', 'editor', 'viewer') DEFAULT 'viewer',
    CreatedAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    LastLoginAt TIMESTAMP,
    FOREIGN KEY (OrgID) REFERENCES Organizations(OrgID) ON DELETE CASCADE,
    INDEX idx_org_role (OrgID, Role)
);

-- Photos table (modified)
CREATE TABLE Photos (
    PhotoID INT AUTO_INCREMENT PRIMARY KEY,
    S3Key VARCHAR(500) NOT NULL,
    Description TEXT,
    OrgID INT NOT NULL,
    UploadedBy INT NOT NULL,
    FileSize BIGINT,
    MimeType VARCHAR(50),
    CreatedAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (OrgID) REFERENCES Organizations(OrgID) ON DELETE CASCADE,
    FOREIGN KEY (UploadedBy) REFERENCES Users(UserID),
    INDEX idx_org_created (OrgID, CreatedAt),
    INDEX idx_uploader (UploadedBy)
);

-- Albums for organizing photos
CREATE TABLE Albums (
    AlbumID INT AUTO_INCREMENT PRIMARY KEY,
    AlbumName VARCHAR(200) NOT NULL,
    OrgID INT NOT NULL,
    CreatedBy INT NOT NULL,
    IsPublic BOOLEAN DEFAULT FALSE,
    CreatedAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (OrgID) REFERENCES Organizations(OrgID) ON DELETE CASCADE,
    FOREIGN KEY (CreatedBy) REFERENCES Users(UserID)
);

-- Photos in Albums (many-to-many)
CREATE TABLE AlbumPhotos (
    AlbumID INT NOT NULL,
    PhotoID INT NOT NULL,
    AddedAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (AlbumID, PhotoID),
    FOREIGN KEY (AlbumID) REFERENCES Albums(AlbumID) ON DELETE CASCADE,
    FOREIGN KEY (PhotoID) REFERENCES Photos(PhotoID) ON DELETE CASCADE
);

-- Cross-organization album sharing
CREATE TABLE SharedAlbums (
    ShareID INT AUTO_INCREMENT PRIMARY KEY,
    AlbumID INT NOT NULL,
    SharedWithOrgID INT NOT NULL,
    Permission ENUM('view', 'contribute') DEFAULT 'view',
    SharedAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (AlbumID) REFERENCES Albums(AlbumID) ON DELETE CASCADE,
    FOREIGN KEY (SharedWithOrgID) REFERENCES Organizations(OrgID) ON DELETE CASCADE,
    UNIQUE KEY unique_share (AlbumID, SharedWithOrgID)
);
```

---

**b) S3 Bucket Strategy Analysis (4 points)**

**Answer:**

| Factor | Separate Buckets | Shared Bucket with Prefixes |
|--------|-----------------|----------------------------|
| **Cost** | Higher (per-bucket charges, more requests) | Lower (single bucket, efficient) |
| **IAM Complexity** | Simpler per-tenant (bucket-level policies) | Complex (prefix-level policies in single policy) |
| **Cross-tenant leak risk** | **Lower** (physical isolation) | Higher (misconfigured prefix could expose data) |
| **Operational overhead** | Higher (manage N buckets) | Lower (single bucket) |
| **Performance** | Potentially better (no prefix enumeration) | Good (S3 handles prefixes well) |
| **Compliance** | Better for regulated industries | May need additional controls |
| **Backup/Migration** | Easier per-tenant | More complex filtering |

**Recommendation: Shared bucket with prefixes** for most cases, with these safeguards:

```
Bucket: gallery-photos-prod
├── org-001/
│   ├── original/
│   │   └── photo1.jpg
│   └── resized/
│       └── photo1.jpg
├── org-002/
│   ├── original/
│   └── resized/
└── org-003/
```

**IAM Policy:**
```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"],
    "Resource": "arn:aws:s3:::gallery-photos-prod/${aws:PrincipalTag/OrgID}/*",
    "Condition": {
      "StringEquals": {
        "aws:PrincipalTag/OrgID": "${s3:prefix}"
      }
    }
  }]
}
```

**For regulated industries (HIPAA, SOC2):** Use separate buckets with:
- Bucket policies restricting cross-account access
- S3 Object Lock for immutability
- CloudTrail logging per bucket

---

**c) Multi-tenant token enhancement (4 points)**

**Answer:**

**New token format:**
```
{email}|{orgId}|{role}|{expirationTimestamp}.{signature}
```

**Example:**
```
user@company.com|org-001|editor|1737331200000.abc123signature==
```

**Token generation:**
```java
public class TenantToken {
    
    public static String generateToken(String email, String orgId, String role, 
                                        String secretKey, long expirationMinutes, 
                                        LambdaLogger logger) {
        try {
            long expirationTime = System.currentTimeMillis() + (expirationMinutes * 60 * 1000);
            
            // Create payload
            String payload = email + "|" + orgId + "|" + role + "|" + expirationTime;
            
            // Sign payload
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] signature = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String signatureBase64 = Base64.getEncoder().encodeToString(signature);
            
            // Token = payload.signature (both parts needed for validation)
            return Base64.getEncoder().encodeToString(payload.getBytes()) + "." + signatureBase64;
            
        } catch (Exception e) {
            logger.log("Token generation failed: " + e.getMessage());
            return null;
        }
    }
    
    public static TokenInfo validateToken(String token, String secretKey, LambdaLogger logger) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 2) {
                return TokenInfo.invalid("Malformed token");
            }
            
            // Decode payload
            String payload = new String(Base64.getDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            String providedSignature = parts[1];
            
            // Verify signature
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] expectedSig = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String expectedSignature = Base64.getEncoder().encodeToString(expectedSig);
            
            if (!MessageDigest.isEqual(expectedSignature.getBytes(), providedSignature.getBytes())) {
                return TokenInfo.invalid("Invalid signature");
            }
            
            // Parse payload
            String[] payloadParts = payload.split("\\|");
            if (payloadParts.length != 4) {
                return TokenInfo.invalid("Invalid payload format");
            }
            
            String email = payloadParts[0];
            String orgId = payloadParts[1];
            String role = payloadParts[2];
            long expiration = Long.parseLong(payloadParts[3]);
            
            // Check expiration
            if (System.currentTimeMillis() > expiration) {
                return TokenInfo.invalid("Token expired");
            }
            
            return new TokenInfo(true, email, orgId, role, null);
            
        } catch (Exception e) {
            logger.log("Token validation error: " + e.getMessage());
            return TokenInfo.invalid("Validation error: " + e.getMessage());
        }
    }
}

class TokenInfo {
    public final boolean valid;
    public final String email;
    public final String orgId;
    public final String role;
    public final String error;
    
    public TokenInfo(boolean valid, String email, String orgId, String role, String error) {
        this.valid = valid;
        this.email = email;
        this.orgId = orgId;
        this.role = role;
        this.error = error;
    }
    
    public static TokenInfo invalid(String error) {
        return new TokenInfo(false, null, null, null, error);
    }
    
    public boolean canEdit() {
        return "admin".equals(role) || "editor".equals(role);
    }
    
    public boolean canDelete() {
        return "admin".equals(role);
    }
}
```

**Usage in Lambda:**
```java
TokenInfo tokenInfo = TenantToken.validateToken(token, secretKey, logger);

if (!tokenInfo.valid) {
    return forbiddenResponse(tokenInfo.error);
}

// Enforce tenant isolation in queries
String sql = "SELECT * FROM Photos WHERE OrgID = ?";
stmt.setString(1, tokenInfo.orgId);  // Always filter by org from token

// Check permissions for write operations
if (isDeleteOperation && !tokenInfo.canDelete()) {
    return forbiddenResponse("Insufficient permissions");
}
```

---

### Question 9 (11 points) - Performance Optimization Challenge

**a) Lazy loading with Intersection Observer (5 points)**

**Answer:**

**Current problem:**
- 500 photos → 500 simultaneous HTTP requests
- Browser limit: ~6 concurrent connections per domain
- 494 requests queued → slow loading, potential timeouts

**Solution with Intersection Observer:**
```javascript
// Create observer once
const thumbnailObserver = new IntersectionObserver((entries, observer) => {
    entries.forEach(entry => {
        if (entry.isIntersecting) {
            const img = entry.target;
            const key = img.dataset.key;
            
            if (key && !img.dataset.loading) {
                img.dataset.loading = 'true';
                fetchThumbnail(key, img);
            }
            
            // Stop observing once we start loading
            observer.unobserve(img);
        }
    });
}, {
    root: null,  // viewport
    rootMargin: '100px',  // Start loading 100px before visible
    threshold: 0.1
});

function renderListOfObjects(listOfObjects) {
    let objectsTable = document.getElementById("objectsTable");
    
    // Cleanup
    objectsTable.querySelectorAll('img').forEach(img => {
        thumbnailObserver.unobserve(img);
        if (img.src.startsWith('blob:')) {
            URL.revokeObjectURL(img.src);
        }
    });
    
    while (objectsTable.firstChild) {
        objectsTable.removeChild(objectsTable.lastChild);
    }
    
    let objectsArray = JSON.parse(listOfObjects);

    for (let i = 0; i < objectsArray.length; i++) {
        let row = document.createElement("tr");
        
        // Image cell with lazy loading
        let imageCell = document.createElement("td");
        let img = document.createElement("img");
        img.style.objectFit = "cover";
        img.style.width = "100px";
        img.style.height = "100px";
        img.alt = "Loading...";
        img.dataset.key = objectsArray[i].S3Key;  // Store key for later
        
        // Placeholder image (data URI or loading spinner)
        img.src = 'data:image/svg+xml,<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><rect fill="%23eee" width="100" height="100"/><text x="50%" y="50%" text-anchor="middle" dy=".3em" fill="%23999">Loading</text></svg>';
        
        imageCell.appendChild(img);
        
        // Observe this image for visibility
        thumbnailObserver.observe(img);
        
        // ... rest of row creation ...
        
        row.appendChild(imageCell);
        // ... append other cells ...
        objectsTable.appendChild(row);
    }
}

// Modified fetchThumbnail with retry limit
function fetchThumbnail(key, imgElement, retryCount = 0) {
    const MAX_RETRIES = 3;
    const body = { "key": "resized-" + key };
    const url = "https://pdgq4una5vr233h5k3emxttyha0pqfzi.lambda-url.us-east-1.on.aws/";

    fetch(url, { method: 'POST', body: JSON.stringify(body) })
        .then(response => {
            if (!response.ok) throw new Error('Not ready');
            return response.blob();
        })
        .then(blob => {
            if (imgElement.src.startsWith('blob:')) {
                URL.revokeObjectURL(imgElement.src);
            }
            imgElement.src = URL.createObjectURL(blob);
            imgElement.alt = key;
            delete imgElement.dataset.loading;
        })
        .catch(error => {
            if (retryCount < MAX_RETRIES) {
                setTimeout(() => fetchThumbnail(key, imgElement, retryCount + 1), 2000);
            } else {
                imgElement.alt = "Unavailable";
                delete imgElement.dataset.loading;
            }
        });
}
```

---

**b) Backend Pagination (3 points)**

**Answer:**

```java
@Override
public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
    LambdaLogger logger = context.getLogger();
    
    // Parse pagination parameters
    int limit = 20;  // default
    int offset = 0;
    
    String body = request.getBody();
    if (body != null && !body.isEmpty() && !body.contains("EventBridge")) {
        try {
            JSONObject params = new JSONObject(body);
            limit = params.optInt("limit", 20);
            offset = params.optInt("offset", 0);
            
            // Enforce max limit
            limit = Math.min(limit, 100);
        } catch (Exception e) {
            logger.log("Error parsing params: " + e.getMessage());
        }
    }
    
    JSONObject result = new JSONObject();
    JSONArray items = new JSONArray();
    int totalCount = 0;
    
    try {
        Class.forName("com.mysql.cj.jdbc.Driver");
        Connection conn = DriverManager.getConnection(JDBC_URL, setMySqlConnectionProperties());
        
        // Get total count
        PreparedStatement countStmt = conn.prepareStatement("SELECT COUNT(*) FROM Photos");
        ResultSet countRs = countStmt.executeQuery();
        if (countRs.next()) {
            totalCount = countRs.getInt(1);
        }
        countRs.close();
        countStmt.close();
        
        // Get paginated results
        PreparedStatement stmt = conn.prepareStatement(
            "SELECT * FROM Photos ORDER BY ID DESC LIMIT ? OFFSET ?"
        );
        stmt.setInt(1, limit);
        stmt.setInt(2, offset);
        
        ResultSet rs = stmt.executeQuery();
        
        while (rs.next()) {
            JSONObject item = new JSONObject();
            item.put("ID", rs.getInt("ID"));
            item.put("Description", rs.getString("Description"));
            item.put("S3Key", rs.getString("S3Key"));
            item.put("Email", rs.getString("Email"));
            items.put(item);
        }
        
        rs.close();
        stmt.close();
        conn.close();
        
    } catch (Exception ex) {
        logger.log("Error: " + ex.getMessage());
    }
    
    // Build response with pagination metadata
    result.put("items", items);
    result.put("totalCount", totalCount);
    result.put("limit", limit);
    result.put("offset", offset);
    result.put("hasMore", offset + items.length() < totalCount);
    
    String encoded = Base64.getEncoder().encodeToString(result.toString().getBytes());
    
    return new APIGatewayProxyResponseEvent()
        .withStatusCode(200)
        .withBody(encoded)
        .withIsBase64Encoded(true)
        .withHeaders(Map.of("Content-Type", "application/json"));
}
```

**Frontend usage:**
```javascript
let currentOffset = 0;
const PAGE_SIZE = 20;

async function loadPhotos(reset = false) {
    if (reset) currentOffset = 0;
    
    const body = {
        email: document.getElementById('email').value,
        token: document.getElementById('token').value,
        limit: PAGE_SIZE,
        offset: currentOffset
    };
    
    const response = await fetch(LIST_URL, {
        method: 'POST',
        body: JSON.stringify(body)
    });
    
    const text = await response.text();
    const data = JSON.parse(atob(text));
    
    renderListOfObjects(JSON.stringify(data.items));
    
    // Update pagination UI
    document.getElementById('totalCount').textContent = data.totalCount;
    document.getElementById('loadMore').style.display = data.hasMore ? 'block' : 'none';
    
    currentOffset += data.items.length;
}
```

---

**c) CDN Integration with CloudFront (3 points)**

**Answer:**

**CloudFront Distribution Setup:**

```yaml
# CloudFormation template
ThumbnailCDN:
  Type: AWS::CloudFront::Distribution
  Properties:
    DistributionConfig:
      Origins:
        - Id: S3ThumbnailOrigin
          DomainName: resized-cloud-public-mpg.s3.amazonaws.com
          S3OriginConfig:
            OriginAccessIdentity: !Sub origin-access-identity/cloudfront/${OAI}
      
      DefaultCacheBehavior:
        TargetOriginId: S3ThumbnailOrigin
        ViewerProtocolPolicy: redirect-to-https
        
        # Cache settings
        CachePolicyId: 658327ea-f89d-4fab-a63d-7e88639e58f6  # CachingOptimized
        
        # Or custom cache policy:
        MinTTL: 86400      # 1 day minimum
        DefaultTTL: 604800  # 1 week default
        MaxTTL: 31536000    # 1 year max
        
        # Compress images
        Compress: true
        
        # Allow query strings for cache busting
        ForwardedValues:
          QueryString: true
          
      # For private photos, use signed URLs
      TrustedSigners:
        - self
      
      PriceClass: PriceClass_100  # US, Canada, Europe only
      Enabled: true

# Origin Access Identity
OAI:
  Type: AWS::CloudFront::CloudFrontOriginAccessIdentity
  Properties:
    CloudFrontOriginAccessIdentityConfig:
      Comment: OAI for thumbnail bucket

# Bucket policy allowing CloudFront
BucketPolicy:
  Type: AWS::S3::BucketPolicy
  Properties:
    Bucket: resized-cloud-public-mpg
    PolicyDocument:
      Statement:
        - Effect: Allow
          Principal:
            AWS: !Sub arn:aws:iam::cloudfront:user/CloudFront Origin Access Identity ${OAI}
          Action: s3:GetObject
          Resource: arn:aws:s3:::resized-cloud-public-mpg/*
```

**For private photos (signed URLs):**

```java
// Generate signed URL in Lambda
public String generateSignedUrl(String key, int expirationMinutes) {
    String distributionDomain = "d1234567890.cloudfront.net";
    String keyPairId = System.getenv("CLOUDFRONT_KEY_PAIR_ID");
    String privateKey = getPrivateKeyFromSecretsManager();
    
    Date expiration = new Date(System.currentTimeMillis() + expirationMinutes * 60 * 1000);
    
    SignedUrlCannedPolicy signedUrl = CloudFrontUrlSigner.getSignedURLWithCannedPolicy(
        "https://" + distributionDomain + "/" + key,
        keyPairId,
        privateKey,
        expiration
    );
    
    return signedUrl;
}
```

**Modified fetchThumbnail:**
```javascript
// Option 1: Direct CloudFront URL (for public thumbnails)
function fetchThumbnailCDN(key, imgElement) {
    const cdnUrl = `https://d1234567890.cloudfront.net/resized-${key}`;
    imgElement.src = cdnUrl;  // Direct URL, no fetch needed!
    imgElement.onerror = () => {
        // Fallback to Lambda if CDN fails
        fetchThumbnailLambda(key, imgElement);
    };
}

// Option 2: Get signed URL from backend (for private thumbnails)
async function fetchThumbnailSigned(key, imgElement) {
    const response = await fetch('/api/thumbnail-url', {
        method: 'POST',
        body: JSON.stringify({ key: key, token: getToken() })
    });
    const { signedUrl } = await response.json();
    imgElement.src = signedUrl;
}
```

---

## Part D: Code Implementation Challenge (25 points)

### Question 10 (25 points) - Build a "Favorites" Feature

**a) Database Design (3 points)**

```sql
CREATE TABLE Favorites (
    FavoriteID INT AUTO_INCREMENT PRIMARY KEY,
    UserEmail VARCHAR(255) NOT NULL,
    PhotoS3Key VARCHAR(500) NOT NULL,
    CreatedAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- Unique constraint: user can only favorite a photo once
    UNIQUE KEY unique_user_photo (UserEmail, PhotoS3Key),
    
    -- Index for fast lookup by user
    INDEX idx_user_email (UserEmail),
    
    -- Index for checking if specific photo is favorited
    INDEX idx_photo_key (PhotoS3Key)
);
```

---

**b) LambdaToggleFavorite (6 points)**

```java
package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import java.sql.*;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;

import org.json.JSONObject;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsUtilities;
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest;

public class LambdaToggleFavorite implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final String RDS_INSTANCE_HOSTNAME = "database-1.c6p4im2uqehz.us-east-1.rds.amazonaws.com";
    private static final int RDS_INSTANCE_PORT = 3306;
    private static final String DB_USER = "cloud26";
    private static final String JDBC_URL = "jdbc:mysql://" + RDS_INSTANCE_HOSTNAME + ":" + RDS_INSTANCE_PORT + "/Cloud26";

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        LambdaLogger logger = context.getLogger();
        JSONObject responseBody = new JSONObject();

        try {
            String requestBody = request.getBody();
            
            if (requestBody != null && requestBody.equals("EventBridgeInvoke")) {
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withBody("No action for EventBridge");
            }

            JSONObject json = new JSONObject(requestBody);
            String email = json.getString("email");
            String photoKey = json.getString("photoKey");

            Class.forName("com.mysql.cj.jdbc.Driver");

            try (Connection conn = DriverManager.getConnection(JDBC_URL, setMySqlConnectionProperties())) {
                
                // Check if favorite already exists
                boolean exists = false;
                try (PreparedStatement checkStmt = conn.prepareStatement(
                        "SELECT 1 FROM Favorites WHERE UserEmail = ? AND PhotoS3Key = ?")) {
                    checkStmt.setString(1, email);
                    checkStmt.setString(2, photoKey);
                    ResultSet rs = checkStmt.executeQuery();
                    exists = rs.next();
                }

                if (exists) {
                    // Remove favorite
                    try (PreparedStatement deleteStmt = conn.prepareStatement(
                            "DELETE FROM Favorites WHERE UserEmail = ? AND PhotoS3Key = ?")) {
                        deleteStmt.setString(1, email);
                        deleteStmt.setString(2, photoKey);
                        deleteStmt.executeUpdate();
                    }
                    responseBody.put("isFavorite", false);
                    responseBody.put("action", "removed");
                    logger.log("Removed favorite: " + email + " -> " + photoKey);
                } else {
                    // Add favorite
                    try (PreparedStatement insertStmt = conn.prepareStatement(
                            "INSERT INTO Favorites (UserEmail, PhotoS3Key) VALUES (?, ?)")) {
                        insertStmt.setString(1, email);
                        insertStmt.setString(2, photoKey);
                        insertStmt.executeUpdate();
                    }
                    responseBody.put("isFavorite", true);
                    responseBody.put("action", "added");
                    logger.log("Added favorite: " + email + " -> " + photoKey);
                }

                responseBody.put("success", true);
                responseBody.put("photoKey", photoKey);
            }

        } catch (Exception ex) {
            logger.log("Error: " + ex.getMessage());
            responseBody.put("success", false);
            responseBody.put("error", ex.getMessage());

            return new APIGatewayProxyResponseEvent()
                .withStatusCode(500)
                .withBody(responseBody.toString())
                .withHeaders(Map.of("Content-Type", "application/json"));
        }

        return new APIGatewayProxyResponseEvent()
            .withStatusCode(200)
            .withBody(responseBody.toString())
            .withHeaders(Map.of("Content-Type", "application/json"));
    }

    private static Properties setMySqlConnectionProperties() throws Exception {
        Properties props = new Properties();
        props.setProperty("useSSL", "true");
        props.setProperty("user", DB_USER);
        props.setProperty("password", generateAuthToken());
        return props;
    }

    private static String generateAuthToken() throws Exception {
        RdsUtilities rdsUtilities = RdsUtilities.builder().build();
        return rdsUtilities.generateAuthenticationToken(
            GenerateAuthenticationTokenRequest.builder()
                .hostname(RDS_INSTANCE_HOSTNAME)
                .port(RDS_INSTANCE_PORT)
                .username(DB_USER)
                .region(Region.US_EAST_1)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build());
    }
}
```

---

**c) LambdaGetFavorites (4 points)**

```java
package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import java.sql.*;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;
// ... imports for RDS auth ...

public class LambdaGetFavorites implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    // ... RDS constants same as above ...

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        LambdaLogger logger = context.getLogger();
        JSONArray favorites = new JSONArray();

        try {
            String requestBody = request.getBody();
            
            if (requestBody != null && requestBody.equals("EventBridgeInvoke")) {
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withBody("No action for EventBridge");
            }

            JSONObject json = new JSONObject(requestBody);
            String email = json.getString("email");

            Class.forName("com.mysql.cj.jdbc.Driver");

            try (Connection conn = DriverManager.getConnection(JDBC_URL, setMySqlConnectionProperties());
                 PreparedStatement stmt = conn.prepareStatement(
                     "SELECT PhotoS3Key, CreatedAt FROM Favorites WHERE UserEmail = ? ORDER BY CreatedAt DESC")) {
                
                stmt.setString(1, email);
                ResultSet rs = stmt.executeQuery();

                while (rs.next()) {
                    JSONObject fav = new JSONObject();
                    fav.put("photoKey", rs.getString("PhotoS3Key"));
                    fav.put("favoritedAt", rs.getTimestamp("CreatedAt").toString());
                    favorites.put(fav);
                }
            }

        } catch (Exception ex) {
            logger.log("Error: " + ex.getMessage());
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(500)
                .withBody("{\"error\": \"" + ex.getMessage() + "\"}")
                .withHeaders(Map.of("Content-Type", "application/json"));
        }

        String encoded = Base64.getEncoder().encodeToString(favorites.toString().getBytes());
        
        return new APIGatewayProxyResponseEvent()
            .withStatusCode(200)
            .withBody(encoded)
            .withIsBase64Encoded(true)
            .withHeaders(Map.of("Content-Type", "application/json"));
    }

    // ... setMySqlConnectionProperties and generateAuthToken same as above ...
}
```

---

**d) Modified LambdaGetPhotosDB (4 points)**

```java
@Override
public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
    LambdaLogger logger = context.getLogger();
    JSONArray items = new JSONArray();

    String content = request.getBody();
    if (content != null && content.contains("EventBridge")) {
        return new APIGatewayProxyResponseEvent()
            .withStatusCode(200)
            .withBody("No action for EventBridge");
    }

    // Parse parameters
    String userEmail = null;
    boolean favoritesOnly = false;
    
    if (content != null && !content.isEmpty()) {
        try {
            JSONObject params = new JSONObject(content);
            userEmail = params.optString("email", null);
            favoritesOnly = params.optBoolean("favoritesOnly", false);
        } catch (Exception e) {
            logger.log("Error parsing params: " + e.getMessage());
        }
    }

    try {
        Class.forName("com.mysql.cj.jdbc.Driver");
        Connection conn = DriverManager.getConnection(JDBC_URL, setMySqlConnectionProperties());

        String sql;
        PreparedStatement stmt;

        if (favoritesOnly && userEmail != null) {
            // Join with Favorites to get only favorites
            sql = "SELECT p.*, " +
                  "CASE WHEN f.FavoriteID IS NOT NULL THEN TRUE ELSE FALSE END as isFavorite " +
                  "FROM Photos p " +
                  "INNER JOIN Favorites f ON p.S3Key = f.PhotoS3Key AND f.UserEmail = ? " +
                  "ORDER BY f.CreatedAt DESC";
            stmt = conn.prepareStatement(sql);
            stmt.setString(1, userEmail);
        } else if (userEmail != null) {
            // Get all photos with favorite status for this user
            sql = "SELECT p.*, " +
                  "CASE WHEN f.FavoriteID IS NOT NULL THEN TRUE ELSE FALSE END as isFavorite " +
                  "FROM Photos p " +
                  "LEFT JOIN Favorites f ON p.S3Key = f.PhotoS3Key AND f.UserEmail = ? " +
                  "ORDER BY p.ID DESC";
            stmt = conn.prepareStatement(sql);
            stmt.setString(1, userEmail);
        } else {
            // No user context, return all without favorite status
            sql = "SELECT *, FALSE as isFavorite FROM Photos ORDER BY ID DESC";
            stmt = conn.prepareStatement(sql);
        }

        ResultSet rs = stmt.executeQuery();

        while (rs.next()) {
            JSONObject item = new JSONObject();
            item.put("ID", rs.getInt("ID"));
            item.put("Description", rs.getString("Description"));
            item.put("S3Key", rs.getString("S3Key"));
            item.put("Email", rs.getString("Email"));
            item.put("isFavorite", rs.getBoolean("isFavorite"));
            items.put(item);
        }

        rs.close();
        stmt.close();
        conn.close();

    } catch (Exception ex) {
        logger.log("Error: " + ex.getMessage());
    }

    String encoded = Base64.getEncoder().encodeToString(items.toString().getBytes());

    return new APIGatewayProxyResponseEvent()
        .withStatusCode(200)
        .withBody(encoded)
        .withIsBase64Encoded(true)
        .withHeaders(Map.of("Content-Type", "application/json"));
}
```

---

**e) Frontend Implementation (8 points)**

```html
<!-- Add to HTML, after the List button -->
<div style="margin: 10px 0;">
    <label>
        <input type="checkbox" id="favoritesOnly" onchange="fetchListOfObjects()">
        Show Favorites Only
    </label>
</div>

<style>
    .favorite-btn {
        background: none;
        border: none;
        cursor: pointer;
        font-size: 20px;
        padding: 5px;
        transition: transform 0.2s;
    }
    .favorite-btn:hover {
        transform: scale(1.2);
    }
    .favorite-btn.favorited {
        color: gold;
    }
    .favorite-btn.not-favorited {
        color: gray;
    }
</style>

<script>
    // Constants
    const TOGGLE_FAVORITE_URL = "https://YOUR_TOGGLE_FAVORITE_LAMBDA.lambda-url.us-east-1.on.aws/";

    // Track pending favorite operations for optimistic updates
    const pendingFavorites = new Set();

    function toggleFavorite(photoKey, buttonElement) {
        // Prevent double-clicks
        if (pendingFavorites.has(photoKey)) {
            return;
        }
        pendingFavorites.add(photoKey);

        const currentlyFavorited = buttonElement.classList.contains('favorited');
        
        // Optimistic UI update
        buttonElement.classList.toggle('favorited');
        buttonElement.classList.toggle('not-favorited');
        buttonElement.innerHTML = currentlyFavorited ? '☆' : '★';

        const body = {
            email: document.getElementById('email').value,
            token: document.getElementById('token').value,
            photoKey: photoKey
        };

        fetch(TOGGLE_FAVORITE_URL, {
            method: 'POST',
            body: JSON.stringify(body)
        })
        .then(response => {
            if (!response.ok) {
                throw new Error('Failed to update favorite');
            }
            return response.json();
        })
        .then(data => {
            console.log('Favorite toggled:', data);
            
            // Verify server state matches optimistic update
            const serverFavorited = data.isFavorite;
            const uiFavorited = buttonElement.classList.contains('favorited');
            
            if (serverFavorited !== uiFavorited) {
                // Correct the UI if server disagrees
                buttonElement.classList.toggle('favorited', serverFavorited);
                buttonElement.classList.toggle('not-favorited', !serverFavorited);
                buttonElement.innerHTML = serverFavorited ? '★' : '☆';
            }
        })
        .catch(error => {
            console.error('Error toggling favorite:', error);
            
            // Revert optimistic update on error
            buttonElement.classList.toggle('favorited');
            buttonElement.classList.toggle('not-favorited');
            buttonElement.innerHTML = currentlyFavorited ? '★' : '☆';
            
            alert('Failed to update favorite. Please try again.');
        })
        .finally(() => {
            pendingFavorites.delete(photoKey);
        });
    }

    // Modified fetchListOfObjects to include favorites filter
    function fetchListOfObjects() {
        const favoritesOnly = document.getElementById('favoritesOnly').checked;
        
        const body = {
            email: document.getElementById('email').value,
            token: document.getElementById('token').value,
            favoritesOnly: favoritesOnly
        };
        
        let url = "https://4ppxzno45ostxgonxljjkeehmm0siotc.lambda-url.us-east-1.on.aws/";
        
        fetch(url, {
            method: 'POST',
            body: JSON.stringify(body)
        })
        .then((response) => {
            if (!response.ok) {
                handleFetchError("Error fetching object list.", response.status);
            }
            return response.text();
        })
        .then((text) => {
            const decodedText = atob(text);
            document.querySelectorAll('.gallery-section').forEach(el => el.classList.add('show'));
            document.getElementById("download_image").src = "";
            renderListOfObjects(decodedText);
        })
        .catch((error) => {
            console.log(`Error: ${error.message}`);
        });
    }

    // Modified renderListOfObjects to include favorite button
    function renderListOfObjects(listOfObjects) {
        let objectsTable = document.getElementById("objectsTable");
        
        // Cleanup existing blob URLs
        objectsTable.querySelectorAll('img').forEach(img => {
            if (img.src && img.src.startsWith('blob:')) {
                URL.revokeObjectURL(img.src);
            }
        });
        
        while (objectsTable.firstChild) {
            objectsTable.removeChild(objectsTable.lastChild);
        }
        
        let objectsArray = JSON.parse(listOfObjects);

        for (let i = 0; i < objectsArray.length; i++) {
            let row = document.createElement("tr");
            
            // Favorite button cell
            let favoriteCell = document.createElement("td");
            let favoriteBtn = document.createElement("button");
            favoriteBtn.className = 'favorite-btn';
            
            const isFavorite = objectsArray[i].isFavorite;
            favoriteBtn.classList.add(isFavorite ? 'favorited' : 'not-favorited');
            favoriteBtn.innerHTML = isFavorite ? '★' : '☆';
            favoriteBtn.title = isFavorite ? 'Remove from favorites' : 'Add to favorites';
            
            // Capture the key in closure
            const photoKey = objectsArray[i].S3Key;
            favoriteBtn.addEventListener("click", function() {
                toggleFavorite(photoKey, favoriteBtn);
            });
            favoriteCell.appendChild(favoriteBtn);
            
            // Image cell
            let imageCell = document.createElement("td");
            let img = document.createElement("img");
            img.style.objectFit = "cover";
            img.alt = "Loading...";
            imageCell.appendChild(img);
            fetchThumbnail(objectsArray[i].S3Key, img);
            
            // Key cell
            let keyCell = document.createElement("td");
            keyCell.innerHTML = objectsArray[i].S3Key;
            
            // Description cell
            let descriptionCell = document.createElement("td");
            descriptionCell.innerHTML = objectsArray[i].Description || "No description";
            
            // Email cell
            let emailCell = document.createElement("td");
            emailCell.innerHTML = objectsArray[i].Email || "No email";
            
            // Download button cell
            let downloadCell = document.createElement("td");
            let downloadButton = document.createElement("button");
            downloadButton.addEventListener("click", function() {
                fetchObject(objectsArray[i].S3Key);
            });
            downloadButton.innerHTML = "Download";
            downloadCell.appendChild(downloadButton);
            
            // Delete button cell
            let deleteCell = document.createElement("td");
            let deleteButton = document.createElement("button");
            deleteButton.addEventListener("click", function() {
                deleteObject(objectsArray[i].S3Key);
            });
            deleteButton.innerHTML = "Delete";
            deleteCell.appendChild(deleteButton);
            
            // Append all cells in order
            row.appendChild(favoriteCell);  // NEW: Favorite button first
            row.appendChild(imageCell);
            row.appendChild(keyCell);
            row.appendChild(descriptionCell);
            row.appendChild(emailCell);
            row.appendChild(downloadCell);
            row.appendChild(deleteCell);
            
            objectsTable.appendChild(row);
        }
    }
</script>
```

---

## Bonus Section Solutions

### Bonus Question 1 (5 points) - The Debugging Detective

**a) Possible causes (at least 5):**

1. **RDS connection timeout**: Database insert takes too long or fails silently
2. **Lambda cold start**: First request after idle period takes longer
3. **S3 eventual consistency** (pre-2020 behavior, now strong consistency)
4. **Double-encoding issue**: Response not properly decoded by frontend
5. **Browser caching**: Old list cached, not refreshing
6. **Token validation delay**: Token checker Lambda slow or failing
7. **Parallel execution race**: DB insert not complete when list is fetched
8. **VPC ENI attachment**: Lambda in VPC takes longer on cold start
9. **DynamoDB throttling** (if used for status tracking)
10. **API Gateway timeout**: Request cut off before completion

**b) CloudWatch Logs diagnosis:**

| Cause | CloudWatch Query/Check |
|-------|----------------------|
| RDS timeout | Look for "Connection timeout" in LambdaUploadDescriptionDB logs |
| Cold start | Check `Init Duration` in Lambda metrics, look for high latency spikes |
| Token delay | Compare timestamps between TokenChecker and next Lambda |
| Parallel race | Check completion order of UploadDescriptionDB vs orchestrator response |

**c) CloudWatch Logs Insights query:**

```sql
fields @timestamp, @message, @requestId
| filter @message like /Upload/ or @message like /GetPhotos/
| parse @message "Upload completed for *" as uploadKey
| parse @message "Fetching photos, found key: *" as fetchedKey
| stats 
    earliest(@timestamp) as uploadTime,
    latest(@timestamp) as fetchTime,
    (latest(@timestamp) - earliest(@timestamp)) / 1000 as latencySeconds
  by uploadKey
| filter latencySeconds > 60
| sort latencySeconds desc
| limit 20
```

---

### Bonus Question 2 (5 points) - SAM Template

```yaml
AWSTemplateFormatVersion: '2010-09-09'
Transform: AWS::Serverless-2016-10-31
Description: Serverless Gallery - Upload Orchestrator

Parameters:
  SourceBucketName:
    Type: String
    Default: cloud-public-mpg
  ResizedBucketName:
    Type: String
    Default: resized-cloud-public-mpg

Globals:
  Function:
    Timeout: 30
    Runtime: java11
    MemorySize: 512

Resources:
  UploadOrchestratorFunction:
    Type: AWS::Serverless::Function
    Properties:
      FunctionName: LambdaUploadOrchestrator
      CodeUri: LambdaUploadOrchestrator/target/
      Handler: vgu.cloud26.LambdaUploadOrchestrator::handleRequest
      
      Environment:
        Variables:
          SOURCE_BUCKET: !Ref SourceBucketName
          RESIZED_BUCKET: !Ref ResizedBucketName
          REGION: !Ref AWS::Region
      
      FunctionUrlConfig:
        AuthType: NONE
        Cors:
          AllowOrigins:
            - "*"
          AllowMethods:
            - PUT
            - POST
          AllowHeaders:
            - Content-Type
      
      Role: !GetAtt UploadOrchestratorRole.Arn
      
      # VPC config if needed for RDS
      # VpcConfig:
      #   SecurityGroupIds:
      #     - !Ref LambdaSecurityGroup
      #   SubnetIds:
      #     - !Ref PrivateSubnet1
      #     - !Ref PrivateSubnet2

  UploadOrchestratorRole:
    Type: AWS::IAM::Role
    Properties:
      RoleName: LambdaUploadOrchestratorRole
      AssumeRolePolicyDocument:
        Version: '2012-10-17'
        Statement:
          - Effect: Allow
            Principal:
              Service: lambda.amazonaws.com
            Action: sts:AssumeRole
      
      Policies:
        - PolicyName: UploadOrchestratorPolicy
          PolicyDocument:
            Version: '2012-10-17'
            Statement:
              # CloudWatch Logs - write only
              - Effect: Allow
                Action:
                  - logs:CreateLogStream
                  - logs:PutLogEvents
                Resource: !Sub arn:aws:logs:${AWS::Region}:${AWS::AccountId}:log-group:/aws/lambda/LambdaUploadOrchestrator:*
              
              # Invoke child Lambda functions
              - Effect: Allow
                Action:
                  - lambda:InvokeFunction
                Resource:
                  - !Sub arn:aws:lambda:${AWS::Region}:${AWS::AccountId}:function:LambdaUploadObject
                  - !Sub arn:aws:lambda:${AWS::Region}:${AWS::AccountId}:function:LambdaImageResizer
                  - !Sub arn:aws:lambda:${AWS::Region}:${AWS::AccountId}:function:LambdaUploadDescriptionDB
                  - !Sub arn:aws:lambda:${AWS::Region}:${AWS::AccountId}:function:LambdaTokenChecker
              
              # Parameter Store read for token validation
              - Effect: Allow
                Action:
                  - ssm:GetParameter
                Resource: !Sub arn:aws:ssm:${AWS::Region}:${AWS::AccountId}:parameter/S3DownloadKey
                Condition:
                  StringEquals:
                    ssm:resourceTag/Environment: production

  UploadOrchestratorLogGroup:
    Type: AWS::Logs::LogGroup
    Properties:
      LogGroupName: /aws/lambda/LambdaUploadOrchestrator
      RetentionInDays: 7

Outputs:
  FunctionUrl:
    Description: Upload Orchestrator Function URL
    Value: !GetAtt UploadOrchestratorFunctionUrl.FunctionUrl
  
  FunctionArn:
    Description: Upload Orchestrator Function ARN
    Value: !GetAtt UploadOrchestratorFunction.Arn
```

---

### Bonus Question 3 (5 points) - Chaos Engineering

**a) Simulating S3 failure:**

```java
// Using AWS Fault Injection Simulator (FIS) or manual approach:

// Option 1: Temporary bucket policy denying access
{
    "Version": "2012-10-17",
    "Statement": [{
        "Effect": "Deny",
        "Principal": "*",
        "Action": "s3:*",
        "Resource": "arn:aws:s3:::cloud-public-mpg/*"
    }]
}

// Option 2: Code-level chaos (feature flag)
public class LambdaUploadObject {
    private boolean chaosEnabled = Boolean.parseBoolean(System.getenv("CHAOS_S3_FAILURE"));
    
    @Override
    public APIGatewayProxyResponseEvent handleRequest(...) {
        if (chaosEnabled && Math.random() < 0.5) {
            throw new RuntimeException("CHAOS: Simulated S3 failure");
        }
        // Normal processing...
    }
}
```

**Expected behavior:** Upload orchestrator should catch exception, return error to user, and (ideally) perform compensation.

---

**b) Circuit breaker for RDS:**

```java
public class RDSCircuitBreaker {
    private static final int FAILURE_THRESHOLD = 5;
    private static final long RESET_TIMEOUT_MS = 30000; // 30 seconds
    
    private static AtomicInteger failureCount = new AtomicInteger(0);
    private static AtomicLong lastFailureTime = new AtomicLong(0);
    private static AtomicBoolean circuitOpen = new AtomicBoolean(false);
    
    public static Connection getConnection(String jdbcUrl, Properties props, LambdaLogger logger) 
            throws SQLException {
        
        // Check if circuit is open
        if (circuitOpen.get()) {
            long timeSinceFailure = System.currentTimeMillis() - lastFailureTime.get();
            
            if (timeSinceFailure < RESET_TIMEOUT_MS) {
                logger.log("Circuit OPEN - failing fast. Time until retry: " + 
                    (RESET_TIMEOUT_MS - timeSinceFailure) + "ms");
                throw new SQLException("Circuit breaker OPEN - RDS unavailable");
            }
            
            // Try to reset (half-open state)
            logger.log("Circuit HALF-OPEN - attempting connection");
        }
        
        try {
            Connection conn = DriverManager.getConnection(jdbcUrl, props);
            
            // Connection successful - reset circuit
            if (circuitOpen.compareAndSet(true, false)) {
                logger.log("Circuit CLOSED - RDS connection restored");
            }
            failureCount.set(0);
            
            return conn;
            
        } catch (SQLException e) {
            int failures = failureCount.incrementAndGet();
            lastFailureTime.set(System.currentTimeMillis());
            
            logger.log("RDS connection failed. Failure count: " + failures);
            
            if (failures >= FAILURE_THRESHOLD) {
                circuitOpen.set(true);
                logger.log("Circuit OPENED after " + failures + " failures");
            }
            
            throw e;
        }
    }
}

// Usage in Lambda:
try {
    Connection conn = RDSCircuitBreaker.getConnection(JDBC_URL, props, logger);
    // Use connection...
} catch (SQLException e) {
    if (e.getMessage().contains("Circuit breaker OPEN")) {
        // Return service unavailable immediately
        return new APIGatewayProxyResponseEvent()
            .withStatusCode(503)
            .withBody("{\"error\": \"Database temporarily unavailable. Please retry later.\"}")
            .withHeaders(Map.of("Retry-After", "30"));
    }
    // Handle other SQL errors...
}
```

---

**c) Lambda throttling behavior and queuing:**

**Current behavior under throttling (10 concurrent executions):**
1. First 10 requests processed normally
2. Subsequent requests receive 429 (TooManyRequestsException)
3. Client gets error immediately
4. No automatic retry or queuing

**Solution with SQS queuing:**

```
┌─────────────┐     ┌─────────────┐     ┌─────────────────┐
│   Client    │────▶│ API Gateway │────▶│ Enqueue Lambda  │
│             │     │             │     │ (fast, always   │
│             │     │             │     │  available)     │
└─────────────┘     └─────────────┘     └────────┬────────┘
                                                  │
                                                  │ SQS
                                                  ▼
                                        ┌─────────────────┐
                                        │  Upload Queue   │
                                        │  (buffered)     │
                                        └────────┬────────┘
                                                  │
                                    Reserved Concurrency: 10
                                                  │
                                                  ▼
                                        ┌─────────────────┐
                                        │ Upload Worker   │
                                        │ Lambda          │
                                        │ (processes at   │
                                        │  controlled     │
                                        │  rate)          │
                                        └─────────────────┘
```

**Configuration:**
```yaml
UploadWorker:
  Type: AWS::Serverless::Function
  Properties:
    ReservedConcurrentExecutions: 10  # Matches throttle limit
    Events:
      SQSEvent:
        Type: SQS
        Properties:
          Queue: !GetAtt UploadQueue.Arn
          BatchSize: 1  # Process one at a time

UploadQueue:
  Type: AWS::SQS::Queue
  Properties:
    VisibilityTimeout: 300  # 5 minutes
    MessageRetentionPeriod: 86400  # 1 day
    RedrivePolicy:
      deadLetterTargetArn: !GetAtt DLQ.Arn
      maxReceiveCount: 3
```

This ensures:
- Requests are never dropped (queued instead)
- Processing rate matches available capacity
- Failed requests retry automatically
- Dead letter queue catches persistent failures

---

## End of Solutions

**Grading Notes:**
- Deep understanding demonstrated through detailed explanations earns full marks
- Partial implementations with correct approach earn 60-80% of points
- Security considerations in code earn bonus recognition
- Production-ready code (error handling, logging) earns full implementation points
