# Cloud Computing Final Exam - Advanced Edition

**Course**: Cloud Computing (Advanced Track)  
**Duration**: 3 hours  
**Total Points**: 120 points  
**Open Book**: Students may bring printed code from Lambda functions and HTML files

---

## Instructions
- This exam tests deep understanding of serverless architecture, security, and distributed systems
- Show your reasoning for partial credit
- Code snippets should be syntactically correct
- Assume AWS region is `us-east-1` unless specified otherwise

---

## Part A: Code Forensics & Bug Hunting (30 points)

### Question 1 (8 points) - The Silent Failure

Examine the `fetchThumbnail` function in index.html:

```javascript
function fetchThumbnail(key, imgElement) {
    const body = { "key": "resized-" + key };
    let url = "https://pdgq4una5vr233h5k3emxttyha0pqfzi.lambda-url.us-east-1.on.aws/";

    fetch(url, { method: 'POST', body: JSON.stringify(body) })
        .then(response => {
            if (!response.ok) { throw new Error('Thumbnail not ready'); }
            return response.blob();
        })
        .then((myBlob) => {
            const objectURL = URL.createObjectURL(myBlob);
            imgElement.src = objectURL;
            imgElement.alt = key;
        })
        .catch((error) => {
            imgElement.alt = "Thumbnail processing...";
            if (key === "cloud-public.html") {
                imgElement.alt = "No thumbnail";
                return;
            }
            setTimeout(() => { fetchThumbnail(key, imgElement); }, 2000);                    
        });
}
```

a) **Memory Leak Analysis**: Each call to `URL.createObjectURL()` allocates memory that is never released. If a user has 50 photos and refreshes the list 10 times, approximately how many blob URLs are leaked? What is the correct cleanup approach? (3 points)

b) **Recursive Stack Analysis**: If the S3 bucket is misconfigured and thumbnails never exist, this function creates infinite recursive calls. However, it uses `setTimeout`. Explain why this does NOT cause a stack overflow, but still causes problems. What specific browser resource gets exhausted? (3 points)

c) **Race Condition**: If `fetchListOfObjects()` is called twice rapidly, and both calls trigger `fetchThumbnail` for the same image, what happens? Draw a timeline showing the potential issue and propose a fix using a Map to track pending requests. (2 points)

---

### Question 2 (10 points) - The Double-Decode Disaster

The system has a complex encoding chain. Trace through this scenario:

**Scenario**: User requests photo list. The data flows:
1. `LambdaGetPhotosDB` → returns `APIGatewayProxyResponseEvent` with `body` = Base64-encoded JSON
2. `LambdaListObjectsOrchestrator` calls `LambdaGetPhotosDB`, extracts `body`, then Base64-encodes it again
3. Frontend receives response, decodes with `atob()`

Given this original data in the database:
```json
[{"ID":1,"S3Key":"test.jpg","Description":"Café photo","Email":"user@test.com"}]
```

a) Write out the EXACT string at each stage of encoding/decoding, showing the Base64 transformations. Pay special attention to the special character "é" in "Café". (4 points)

b) The `callLambda` helper extracts `body` using `responseObject.optString("body", "")`. What happens if `LambdaGetPhotosDB` returns an error with no `body` field? Trace the error propagation. (3 points)

c) Propose a cleaner architecture that avoids double-encoding. What changes are needed in `LambdaListObjectsOrchestrator` and `LambdaGetPhotosDB`? Consider that Lambda Function URLs automatically handle Base64 encoding for binary responses. (3 points)

---

### Question 3 (12 points) - Security Vulnerability Assessment

Examine the token system and identify vulnerabilities:

**LambdaTokenGenerator** creates tokens using:
```java
Mac mac = Mac.getInstance("HmacSHA256");
SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
mac.init(secretKeySpec);
byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
String base64 = Base64.getEncoder().encodeToString(hmacBytes);
```

Where `data` = email address and `key` = secret from Parameter Store.

a) **Token Reuse Attack**: The token has no expiration. If an attacker obtains a valid token for `admin@company.com`, how long can they use it? Propose a solution that includes timestamp in the token while maintaining the HMAC integrity. Write the modified `generateSecureToken` method. (4 points)

b) **Email Enumeration**: The `LambdaTokenChecker` returns `{"success": false, "error": "Invalid token"}` for wrong tokens. Can an attacker determine if an email exists in the system? How? Propose a fix. (2 points)

c) **Parameter Store Access**: The Lambda retrieves the secret key via:
```java
URI.create("http://localhost:2773/systemsmanager/parameters/get/?name=S3DownloadKey&withDecryption=true")
```
What happens if an attacker gains code execution inside the Lambda? What AWS security feature could limit the blast radius? (3 points)

d) **Thumbnail Endpoint Abuse**: `LambdaFetchThumbnails` has no authentication. Write a malicious script that could enumerate all photos in the system by brute-forcing the `resized-{timestamp}_{uuid}.jpg` pattern. What's the practical limitation that makes this attack difficult? (3 points)

---

## Part B: Distributed Systems Deep Dive (30 points)

### Question 4 (10 points) - Failure Modes & Recovery

The `LambdaUploadOrchestrator` performs this workflow:
```
TokenCheck → [UploadOriginal || ResizeImage] → [UploadResized || UploadDescriptionDB]
```

a) **Partial Failure Scenario**: The upload succeeds for both original and resized images, but `LambdaUploadDescriptionDB` fails due to RDS connection timeout. What is the system state? How would a user experience this? (2 points)

b) **Compensation Transaction**: Design a cleanup mechanism that runs when `LambdaUploadDescriptionDB` fails. It should delete the already-uploaded S3 objects. Write the Java code that wraps the existing parallel execution in a try-catch with compensation logic. (4 points)

c) **Idempotency Problem**: If the orchestrator times out but the child Lambdas actually succeeded, the user might retry. The unique filename uses `System.currentTimeMillis() + "_" + UUID.randomUUID()`. Will retry create duplicates? How would you implement idempotency using a DynamoDB table? (4 points)

---

### Question 5 (10 points) - Concurrency & Race Conditions

```java
// LambdaUploadOrchestrator parallel execution
CompletableFuture<String> uploadOriginalFuture = callLambdaAsync("LambdaUploadObject", ...);
CompletableFuture<String> resizeFuture = callLambdaAsync("LambdaImageResizer", ...);

String uploadOriginalResponse = uploadOriginalFuture.get();
String resizeResponse = resizeFuture.get();
```

a) **Thread Pool Exhaustion**: `callLambdaAsync` uses `CompletableFuture.supplyAsync()` with the default ForkJoinPool. If 100 concurrent uploads happen, each spawning 4 async operations, how many threads are needed? What happens when the pool is exhausted? (3 points)

b) **Timeout Cascade**: `uploadOriginalFuture.get()` blocks indefinitely. If `LambdaUploadObject` hangs, what happens to the orchestrator Lambda? Write a modified version using `get(timeout, TimeUnit)` with proper exception handling. (3 points)

c) **S3 Eventual Consistency**: After uploading to S3, the object might not be immediately visible for read. If `LambdaGetPhotosDB` is called immediately after upload completes, could it return stale data? Explain the consistency model for:
   - S3 PUT of new object
   - S3 DELETE
   - RDS MySQL read-after-write (4 points)

---

### Question 6 (10 points) - Lambda Internals

a) **Cold Start Anatomy**: The `LambdaUploadDescriptionDB` function connects to RDS using IAM authentication. List ALL the steps that happen during a cold start, from container creation to first SQL query. Estimate time for each step. (4 points)

b) **Connection Pooling Problem**: Each Lambda invocation creates a new database connection:
```java
Connection conn = DriverManager.getConnection(JDBC_URL, setMySqlConnectionProperties());
```
If Lambda handles 1000 requests/second with 100ms average duration, how many concurrent RDS connections are needed? What is the typical RDS connection limit? How would you implement connection reuse across warm Lambda invocations? (3 points)

c) **Memory vs. CPU Tradeoff**: Lambda allocates CPU proportionally to memory. `LambdaImageResizer` does heavy image processing. Currently set to 512MB, it takes 3 seconds. If you increase to 1024MB, estimate the new execution time. Will doubling memory double the cost? Show your calculation. (3 points)

---

## Part C: Architecture Evolution (35 points)

### Question 7 (12 points) - Event-Driven Redesign

The current synchronous orchestration has limitations. Redesign using event-driven architecture.

a) **Design an SQS-based upload pipeline**:
   - User uploads to a "staging" S3 bucket
   - S3 event triggers Lambda to validate and queue the job
   - Worker Lambda processes from SQS
   - Results stored in DynamoDB for status tracking
   
   Draw the architecture diagram and explain how you handle:
   - Duplicate messages (SQS at-least-once delivery)
   - Failed processing (DLQ)
   - User notification of completion (5 points)

b) **Calculate cost comparison**: Given 10,000 uploads/day, each upload taking 5 seconds of orchestrator time in current design vs. 500ms of queue processing in new design. Lambda cost is $0.0000166667 per GB-second at 512MB. Show the monthly cost for both architectures. (4 points)

c) **Migration strategy**: How would you migrate from synchronous to event-driven without downtime? The frontend expects immediate response. Propose a phased approach. (3 points)

---

### Question 8 (12 points) - Multi-Tenant Evolution

The gallery needs to support multiple organizations with data isolation.

a) **Database Schema**: Currently, `Photos` table has `Email` column. Design a multi-tenant schema supporting:
   - Organizations with multiple users
   - Role-based access (admin, editor, viewer)
   - Shared albums between organizations
   
   Write the CREATE TABLE statements for all required tables. (4 points)

b) **S3 Bucket Strategy**: Should each tenant have their own bucket or use prefixes in a shared bucket? Analyze:
   - Cost implications
   - IAM policy complexity
   - Cross-tenant data leak risk
   - Operational overhead
   
   Recommend an approach with justification. (4 points)

c) **Token Enhancement**: Modify the token system to include tenant context. The token should encode:
   - User email
   - Organization ID
   - Role
   - Expiration timestamp
   
   Write a new token format and the validation logic. (4 points)

---

### Question 9 (11 points) - Performance Optimization Challenge

Users report slow gallery loading with 500+ photos.

a) **Diagnose the bottleneck**: The `renderListOfObjects` function creates DOM elements in a loop and calls `fetchThumbnail` for each. With 500 photos:
   - How many HTTP requests are made simultaneously?
   - What is the browser's typical limit for concurrent connections per domain?
   - What happens to requests beyond this limit?
   
   Propose a solution using intersection observer for lazy loading. Write the JavaScript code. (5 points)

b) **Backend Pagination**: Modify `LambdaGetPhotosDB` to support pagination with:
   - `limit` parameter (default 20)
   - `offset` parameter
   - Return `totalCount` for UI
   
   Write the modified SQL query and Java code. (3 points)

c) **CDN Integration**: Thumbnails are good candidates for CDN caching. Design a CloudFront distribution setup:
   - Origin: S3 resized bucket
   - Cache behavior settings
   - How to handle private photos (signed URLs vs. cookies)
   
   What changes are needed in `fetchThumbnail`? (3 points)

---

## Part D: Code Implementation Challenge (25 points)

### Question 10 (25 points) - Build a "Favorites" Feature

Implement a complete "Favorites" feature allowing users to mark photos as favorites and filter by favorites.

**Requirements**:
1. Users can mark/unmark any photo as favorite
2. Favorites are per-user (each user has their own favorites)
3. Gallery can be filtered to show only favorites
4. Favorite status persists across sessions

**Deliverables**:

a) **Database Design** (3 points)
   - Write the SQL to create necessary table(s)
   - Consider indexing strategy

b) **Lambda: LambdaToggleFavorite** (6 points)
   - Complete Java implementation
   - Handle both add and remove in single endpoint
   - Return updated favorite status

c) **Lambda: LambdaGetFavorites** (4 points)
   - Return list of photo S3Keys that are favorites for a user
   - Efficient query design

d) **Modify LambdaGetPhotosDB** (4 points)
   - Add optional `favoritesOnly` parameter
   - Join with favorites table when filtering
   - Include `isFavorite` boolean in each photo response

e) **Frontend Implementation** (8 points)
   - Add favorite button (star icon) to each photo row
   - Toggle functionality with optimistic UI update
   - Add "Show Favorites Only" checkbox filter
   - Handle error cases gracefully

Write complete, production-ready code for all components.

---

## Bonus Section (15 points)

### Bonus Question 1 (5 points) - The Debugging Detective

A user reports: "Sometimes my uploads succeed but the photo doesn't appear in the list for several minutes, even after refreshing."

Using your knowledge of the system:
a) List all possible causes (at least 5)
b) For each cause, describe how you would diagnose it using CloudWatch Logs
c) Write a CloudWatch Logs Insights query to find upload-to-list latency

---

### Bonus Question 2 (5 points) - Infrastructure as Code

Write a complete AWS SAM (Serverless Application Model) template that deploys:
- `LambdaUploadOrchestrator` with Function URL
- Required IAM role with least-privilege permissions
- Environment variables for bucket names
- CloudWatch Log Group with 7-day retention

---

### Bonus Question 3 (5 points) - The Chaos Engineer

You need to test system resilience. Design chaos experiments for:

a) **S3 Failure**: How would you simulate S3 being unavailable? What should happen to uploads?

b) **RDS Failure**: If RDS is down, uploads fail. Design a circuit breaker pattern that:
   - Detects RDS failures
   - Fails fast instead of waiting for timeout
   - Automatically recovers when RDS is back

c) **Lambda Throttling**: AWS throttles your Lambda to 10 concurrent executions. How does the current system behave? What queuing mechanism would help?

---

## End of Exam

**Grading Rubric**:
- Part A (Code Forensics): 30 points
- Part B (Distributed Systems): 30 points  
- Part C (Architecture): 35 points
- Part D (Implementation): 25 points
- Bonus: Up to 15 additional points

Total: 120 points (135 with bonus)

Good luck! 🚀
