# Cloud Computing Final Exam Solutions - January 2026

---

## Part A: System Architecture Understanding (25 points)

### Question 1 (5 points)

**a) Which JavaScript function is triggered? (1 point)**

**Answer:** `triggerUploadFileAndDescription()`

Reference: [index.html](demo/src/main/webapp/index.html) line 46:
```html
<button id="upload_button" onclick="triggerUploadFileAndDescription()">Upload</button>
```

---

**b) What is the Lambda URL endpoint that receives the upload request? (1 point)**

**Answer:** `https://qvvso5khyuv4ficypqlgc4heae0layfh.lambda-url.us-east-1.on.aws/`

Reference: [index.html](demo/src/main/webapp/index.html) line 307

---

**c) List all the data fields that are sent in the request body to the Lambda function. (3 points)**

**Answer:** The request body contains 5 fields:
1. `content` - Base64-encoded file content
2. `key` - Original filename (file.name)
3. `description` - User-provided description
4. `email` - User's email address
5. `token` - Authentication token

Reference: [index.html](demo/src/main/webapp/index.html) lines 312-318:
```javascript
const body = {
    "content": uint8Array.toBase64(),
    "key": file.name,
    "description": description,
    "email": document.getElementById('email').value,
    "token": document.getElementById('token').value
};
```

---

### Question 2 (8 points)

**a) In what order are the following Lambda functions invoked? (2 points)**

**Answer:** The correct order is:
1. `LambdaTokenChecker` (Step 0 - must be first for security)
2. `LambdaUploadObject` (Step 1 - parallel with step 2)
3. `LambdaImageResizer` (Step 2 - parallel with step 1)
4. `LambdaUploadObject` (Step 3 - for resized image, parallel with step 4)
5. `LambdaUploadDescriptionDB` (Step 4 - parallel with step 3)

Reference: [LambdaUploadOrchestrator.java](LambdaUploadOrchestrator/src/main/java/vgu/cloud26/LambdaUploadOrchestrator.java) lines 94-164

---

**b) Which operations run in parallel (concurrently) and which run sequentially? (3 points)**

**Answer:**

**Sequential operations:**
- Token validation MUST run first (security requirement - cannot proceed without valid token)
- Resize operation must complete before uploading resized image (dependency)

**Parallel operations:**
- Step 1 & 2: Upload original AND Resize image run in parallel
- Step 3 & 4: Upload resized image AND Upload description to DB run in parallel

**Why sequential is necessary:**
- Token validation prevents unauthorized uploads
- Cannot upload resized image until resize is complete (data dependency)

---

**c) What is the naming convention used for generating unique filenames? (3 points)**

**Answer:** 
Pattern: `timestamp_UUID.extension`

Java code:
```java
String ext = objName.substring(objName.lastIndexOf('.'));
String uniqueFilename = System.currentTimeMillis() + "_" + UUID.randomUUID().toString() + ext;
```

Example output: `1737283200000_550e8400-e29b-41d4-a716-446655440000.jpg`

Reference: [LambdaUploadOrchestrator.java](LambdaUploadOrchestrator/src/main/java/vgu/cloud26/LambdaUploadOrchestrator.java) lines 88-89

---

### Question 3 (7 points)

**a) When a user clicks the "Delete" button in the gallery, which Lambda orchestrator is called? (1 point)**

**Answer:** `LambdaDeleteOrchestrator`

The delete URL in index.html calls: `https://amemfkr74omup6c6ycoxtf42ge0wyofo.lambda-url.us-east-1.on.aws/`

Reference: [index.html](demo/src/main/webapp/index.html) lines 77-93

---

**b) What are the three resources that need to be deleted? (3 points)**

**Answer:**
| Resource | Lambda Function | Bucket/Database |
|----------|-----------------|-----------------|
| 1. Original image | `LambdaDeleteObject` | `cloud-public-mpg` |
| 2. Resized thumbnail | `LambdaDeleteObject` | `resized-cloud-public-mpg` |
| 3. Database record | `LambdaDeleteDescriptionDB` | RDS MySQL (Photos table) |

Reference: [LambdaDeleteOrchestrator.java](LambdaDeleteOrchestrator/src/main/java/vgu/cloud26/LambdaDeleteOrchestrator.java) lines 103-136

---

**c) Why is it safe to run all three deletions concurrently? (3 points)**

**Answer:**
1. **No data dependencies:** Each deletion targets a different resource (two S3 buckets, one database) - they don't depend on each other's results
2. **Idempotent operations:** Deleting something that's already deleted won't cause errors in S3 or MySQL
3. **No consistency requirement:** There's no transaction that spans all three resources; partial failure is acceptable (orphaned data can be cleaned up later)
4. **Eventual consistency is acceptable:** Even if one deletion fails, the system remains in a valid state

---

### Question 4 (5 points)

**a) Which algorithm is used for generating the authentication token? (1 point)**

**Answer:** **HMAC-SHA256** (Hash-based Message Authentication Code using SHA-256)

Reference: [LambdaTokenGenerator.java](LambdaTokenGenerator/src/main/java/vgu/cloud26/LambdaTokenGenerator.java) lines 30-31:
```java
Mac mac = Mac.getInstance("HmacSHA256");
```

---

**b) Where is the secret key stored and how does the Lambda function retrieve it? (2 points)**

**Answer:**
- **Storage:** AWS Systems Manager Parameter Store (parameter name: `S3DownloadKey`)
- **Retrieval:** Uses the AWS Lambda Parameters and Secrets Extension via localhost HTTP endpoint

```java
HttpRequest requestParameter = HttpRequest.newBuilder()
    .uri(URI.create("http://localhost:2773/systemsmanager/parameters/get/?name=S3DownloadKey&withDecryption=true"))
    .header("X-Aws-Parameters-Secrets-Token", sessionToken)
    .GET()
    .build();
```

Reference: [LambdaTokenChecker.java](LambdaTokenChecker/src/main/java/vgu/cloud26/LambdaTokenChecker.java) lines 91-99

---

**c) Why does the frontend store the token in an input field? (2 points)**

**Answer:**
- **Simplicity:** Easy to copy/paste and share for testing purposes
- **Visibility:** User can see and verify their token
- **No persistence:** Token is lost on page refresh (intentional for security in some cases)

**Security implications:**
- **Negative:** Token is visible on screen (shoulder surfing risk)
- **Negative:** Not automatically sent with requests (must manually include)
- **Positive:** Avoids XSS attacks that target localStorage/cookies
- **Positive:** User has explicit control over when to use the token

---

## Part B: Code Modification Tasks (45 points)

### Question 5 (12 points) - Upload Timestamp Feature

**a) Which Lambda function needs to be modified to store the timestamp? (3 points)**

**Answer:** `LambdaUploadDescriptionDB`

File: [LambdaUploadDescriptionDB.java](LambdaUploadDescriptionDB/src/main/java/vgu/cloud26/LambdaUploadDescriptionDB.java)

Modified SQL statement:
```java
PreparedStatement stmt = conn.prepareStatement(
    "INSERT INTO Photos (Description, S3Key, Email, UploadDate) VALUES (?, ?, ?, NOW())")
```

Or to pass timestamp from Java:
```java
PreparedStatement stmt = conn.prepareStatement(
    "INSERT INTO Photos (Description, S3Key, Email, UploadDate) VALUES (?, ?, ?, ?)");
stmt.setString(1, description);
stmt.setString(2, imageKey);
stmt.setString(3, email);
stmt.setTimestamp(4, new java.sql.Timestamp(System.currentTimeMillis()));
```

---

**b) Which Lambda function retrieves photo metadata? (3 points)**

**Answer:** `LambdaGetPhotosDB`

File: [LambdaGetPhotosDB.java](LambdaGetPhotoDB/src/main/java/vgu/cloud26/LambdaGetPhotosDB.java)

Modified code to include timestamp:
```java
while (rs.next()) {
    JSONObject item = new JSONObject();
    item.put("ID", rs.getInt("ID"));
    item.put("Description", rs.getString("Description"));
    item.put("S3Key", rs.getString("S3Key"));
    item.put("Email", rs.getString("Email"));
    item.put("UploadDate", rs.getTimestamp("UploadDate").toString()); // ADD THIS LINE
    items.put(item);
}
```

---

**c) Modify renderListOfObjects function (4 points)**

**Answer:**
```javascript
function renderListOfObjects(listOfObjects) {
    let objectsTable = document.getElementById("objectsTable");
    while (objectsTable.firstChild) {
        objectsTable.removeChild(objectsTable.lastChild);
    }
    let objectsArray = JSON.parse(listOfObjects);

    for (let i = 0; i < objectsArray.length; i = i + 1) {
        let row = document.createElement("tr");
        
        // ... existing cells ...
        
        /* ADD: Upload timestamp cell */
        let timestampCell = document.createElement("td");
        let uploadDate = objectsArray[i].UploadDate || "Unknown";
        timestampCell.innerHTML = uploadDate;
        
        // ... existing cells ...
        
        row.appendChild(imageCell);
        row.appendChild(keyCell);
        row.appendChild(descriptionCell);
        row.appendChild(emailCell);
        row.appendChild(timestampCell);  // ADD THIS LINE
        row.appendChild(downloadCell);
        row.appendChild(deleteCell);
        objectsTable.appendChild(row);
    }
}
```

---

**d) Database schema change (2 points)**

**Answer:**
```sql
ALTER TABLE Photos 
ADD COLUMN UploadDate TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
```

---

### Question 6 (10 points) - Search by Email Feature

**a) HTML code for search input and button (2 points)**

**Answer:**
```html
<div>
    <label for="searchEmail">Search by Email:</label>
    <input type="text" id="searchEmail" name="searchEmail" placeholder="Enter email to search...">
    <button id="searchButton" onclick="searchByEmail()">Search</button>
    <button id="clearSearchButton" onclick="fetchListOfObjects()">Clear Search</button>
</div>
```

---

**b) JavaScript function searchByEmail() (4 points)**

**Answer:**
```javascript
function searchByEmail() {
    const searchEmail = document.getElementById('searchEmail').value;
    
    if (!searchEmail || searchEmail.trim() === '') {
        alert('Please enter an email to search');
        return;
    }
    
    const body = {
        "email": document.getElementById('email').value,
        "token": document.getElementById('token').value,
        "searchEmail": searchEmail
    };
    
    let url = "https://4ppxzno45ostxgonxljjkeehmm0siotc.lambda-url.us-east-1.on.aws/";
    
    fetch(url, {
        method: 'POST',
        body: JSON.stringify(body)
    })
    .then((response) => {
        if (!response.ok) {
            handleFetchError("Error searching photos.", response.status);
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
        console.error('Search error:', error);
        alert('Error searching: ' + error.message);
    });
}
```

---

**c) Modified LambdaGetPhotosDB Java code (4 points)**

**Answer:**
```java
@Override
public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
    LambdaLogger logger = context.getLogger();
    JSONArray items = new JSONArray();

    String content = request.getBody();
    if (content != null && content.contains("EventBridge")) {
        // ... existing EventBridge handling ...
    }

    try {
        Class.forName("com.mysql.cj.jdbc.Driver");
        Connection mySQLClient = DriverManager.getConnection(JDBC_URL, setMySqlConnectionProperties());

        // Parse optional email filter
        String emailFilter = null;
        if (content != null && !content.isEmpty()) {
            try {
                JSONObject bodyJSON = new JSONObject(content);
                emailFilter = bodyJSON.optString("searchEmail", null);
            } catch (Exception e) {
                logger.log("No filter provided, returning all photos");
            }
        }

        // Build SQL query with optional filter
        String sql;
        PreparedStatement st;
        
        if (emailFilter != null && !emailFilter.isEmpty()) {
            sql = "SELECT * FROM Photos WHERE Email = ?";
            st = mySQLClient.prepareStatement(sql);
            st.setString(1, emailFilter);
        } else {
            sql = "SELECT * FROM Photos";
            st = mySQLClient.prepareStatement(sql);
        }

        ResultSet rs = st.executeQuery();
        
        // ... rest of existing code to build JSON array ...
    }
    // ... rest of method ...
}
```

---

### Question 7 (8 points) - Change Thumbnail Size

**a) Which Lambda function(s) need to be modified? (2 points)**

**Answer:** Only `LambdaImageResizer` needs to be modified.

File path: `LambdaImageResize/src/main/java/vgu/cloud26/LambdaImageResizer.java`

Note: The S3 event-triggered `LambdaResize` (if it exists separately) would also need modification, but based on the current architecture, `LambdaImageResizer` is the API-based resizer used by the orchestrator.

---

**b) What specific line(s) of code need to change? (3 points)**

**Answer:**

**Before (line 37):**
```java
private static final float MAX_DIMENSION = 100;
```

**After:**
```java
private static final float MAX_DIMENSION = 200;
```

---

**c) Will existing thumbnails be affected? (3 points)**

**Answer:** **No**, existing thumbnails will NOT be automatically affected.

**Explanation:**
- Thumbnails are generated at upload time and stored in the `resized-cloud-public-mpg` bucket
- Existing thumbnails remain at 100px until manually regenerated

**To update existing thumbnails:**
1. **Option 1 - Re-upload:** Have users re-upload their photos (not practical)
2. **Option 2 - Batch script:** Create a script that:
   - Lists all objects in `cloud-public-mpg`
   - For each object, calls `LambdaImageResizer` with the original image
   - Uploads the new thumbnail to `resized-cloud-public-mpg`
3. **Option 3 - S3 Batch Operations:** Use S3 Batch Operations to trigger the resize Lambda for all existing objects

---

### Question 8 (15 points) - Rename Photo Description Feature

**a) LambdaUpdateDescriptionDB Java handler (6 points)**

**Answer:**
```java
/*
Function: LambdaUpdateDescriptionDB
Description: Update photo description in RDS database given the S3 key and new description.
*/

package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Base64;
import java.util.Collections;
import java.util.Properties;

import org.json.JSONObject;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsUtilities;
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest;

public class LambdaUpdateDescriptionDB
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final String RDS_INSTANCE_HOSTNAME = "database-1.c6p4im2uqehz.us-east-1.rds.amazonaws.com";
    private static final int RDS_INSTANCE_PORT = 3306;
    private static final String DB_USER = "cloud26";
    private static final String JDBC_URL = "jdbc:mysql://" + RDS_INSTANCE_HOSTNAME + ":" + RDS_INSTANCE_PORT + "/Cloud26";

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        LambdaLogger logger = context.getLogger();

        try {
            String requestBody = request.getBody();
            
            if (requestBody != null && requestBody.equals("EventBridgeInvoke")) {
                logger.log("Invoked by EventBridge, no action taken.");
                return new APIGatewayProxyResponseEvent()
                        .withStatusCode(200)
                        .withBody("No action taken for EventBridge invocation.");
            }
            
            JSONObject json = new JSONObject(requestBody);
            String imageKey = json.getString("imageKey");
            String newDescription = json.getString("description");

            Class.forName("com.mysql.cj.jdbc.Driver");

            try (Connection conn = DriverManager.getConnection(JDBC_URL, setMySqlConnectionProperties());
                    PreparedStatement stmt = conn.prepareStatement(
                            "UPDATE Photos SET Description = ? WHERE S3Key = ?")) {
                stmt.setString(1, newDescription);
                stmt.setString(2, imageKey);
                int rowsAffected = stmt.executeUpdate();
                logger.log("Updated " + rowsAffected + " row(s) for key: " + imageKey);
                
                if (rowsAffected == 0) {
                    return new APIGatewayProxyResponseEvent()
                            .withStatusCode(404)
                            .withBody("{\"message\":\"Photo not found\"}")
                            .withHeaders(Collections.singletonMap("Content-Type", "application/json"));
                }
            }

        } catch (Exception ex) {
            logger.log("Error: " + ex.getMessage());
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withBody("{\"message\":\"Error updating description\"}")
                    .withHeaders(Collections.singletonMap("Content-Type", "application/json"));
        }

        String encodedResult = Base64.getEncoder().encodeToString("Update description success".getBytes());

        return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withBody(encodedResult)
                .withIsBase64Encoded(true)
                .withHeaders(Collections.singletonMap("Content-Type", "application/json"));
    }

    private static Properties setMySqlConnectionProperties() throws Exception {
        Properties mysqlConnectionProperties = new Properties();
        mysqlConnectionProperties.setProperty("useSSL", "true");
        mysqlConnectionProperties.setProperty("user", DB_USER);
        mysqlConnectionProperties.setProperty("password", generateAuthToken());
        return mysqlConnectionProperties;
    }

    private static String generateAuthToken() throws Exception {
        RdsUtilities rdsUtilities = RdsUtilities.builder().build();
        String authToken = rdsUtilities.generateAuthenticationToken(
                GenerateAuthenticationTokenRequest.builder()
                        .hostname(RDS_INSTANCE_HOSTNAME)
                        .port(RDS_INSTANCE_PORT)
                        .username(DB_USER)
                        .region(Region.US_EAST_1)
                        .credentialsProvider(DefaultCredentialsProvider.create())
                        .build());
        return authToken;
    }
}
```

---

**b) JavaScript function updateDescription(key) (5 points)**

**Answer:**
```javascript
function updateDescription(key) {
    let newDescription = prompt("Enter new description for this photo:", "");
    
    if (newDescription === null) {
        // User cancelled
        return;
    }
    
    if (newDescription.trim() === "") {
        alert("Description cannot be empty");
        return;
    }
    
    let url = "https://YOUR_UPDATE_LAMBDA_URL.lambda-url.us-east-1.on.aws/";
    
    const body = {
        "email": document.getElementById('email').value,
        "token": document.getElementById('token').value,
        "imageKey": key,
        "description": newDescription
    };
    
    fetch(url, {
        method: 'PUT',
        body: JSON.stringify(body)
    })
    .then((resp) => {
        if (!resp.ok) {
            handleFetchError("Error updating description.", resp.status);
        }
        return resp.text();
    })
    .then(function(response) {
        console.info('Update response:', response);
        alert("Description updated successfully!");
        fetchListOfObjects(); // Refresh the gallery
    })
    .catch((error) => {
        console.error('Update error:', error);
        alert("Failed to update description: " + error.message);
    });
}
```

---

**c) Modify renderListOfObjects to add Edit button (4 points)**

**Answer:**
```javascript
// Inside the for loop in renderListOfObjects, after deleteCell creation:

/* Edit button cell */
let editCell = document.createElement("td");
let editButton = document.createElement("button");
editButton.addEventListener("click", function() {
    updateDescription(objectsArray[i].S3Key);
});
editButton.innerHTML = "Edit";
editCell.appendChild(editButton);

// In the row.appendChild section, add:
row.appendChild(editCell);
```

Complete addition to the existing code:
```javascript
let editCell = document.createElement("td");
let editButton = document.createElement("button");
editButton.addEventListener("click", function () {
    updateDescription(objectsArray[i].S3Key);
});
editButton.innerHTML = "Edit";
editCell.appendChild(editButton);

row.appendChild(imageCell);
row.appendChild(keyCell);
row.appendChild(descriptionCell);
row.appendChild(emailCell);
row.appendChild(downloadCell);
row.appendChild(editCell);    // ADD THIS LINE
row.appendChild(deleteCell);
```

---

## Part C: Debugging & Troubleshooting (15 points)

### Question 9 (5 points)

**a) What is the retry mechanism in fetchThumbnail()? (2 points)**

**Answer:**
The retry mechanism uses `setTimeout` to retry fetching the thumbnail after 2 seconds if the initial fetch fails:

```javascript
.catch((error) => {
    imgElement.alt = "Thumbnail processing...";
    if (key === "cloud-public.html") {
        imgElement.alt = "No thumbnail";
        return;
    }
    // Retry after 2 seconds
    setTimeout(() => {
        fetchThumbnail(key, imgElement);
    }, 2000);                    
});
```

---

**b) Potential issue if S3 resized bucket is not properly configured (1.5 points)**

**Answer:**
- If the resized bucket doesn't exist or Lambda doesn't have permissions, every fetch will fail
- The function will infinitely retry every 2 seconds
- This causes continuous network requests, wasting bandwidth and potentially hitting rate limits
- Browser may slow down with multiple simultaneous recursive calls

---

**c) How to add a maximum retry limit (1.5 points)**

**Answer:**
```javascript
function fetchThumbnail(key, imgElement, retryCount = 0) {
    const MAX_RETRIES = 5;  // Maximum 5 retries (10 seconds total)
    
    const body = {
        "key": "resized-" + key
    };
    let url = "https://pdgq4una5vr233h5k3emxttyha0pqfzi.lambda-url.us-east-1.on.aws/";

    fetch(url, {
        method: 'POST',
        body: JSON.stringify(body)
    })
    .then(response => {
        if (!response.ok) {
            throw new Error('Thumbnail not ready');
        }
        return response.blob();
    })
    .then((myBlob) => {
        const objectURL = URL.createObjectURL(myBlob);
        imgElement.src = objectURL;
        imgElement.alt = key;
    })
    .catch((error) => {
        if (key === "cloud-public.html") {
            imgElement.alt = "No thumbnail";
            return;
        }
        
        if (retryCount < MAX_RETRIES) {
            imgElement.alt = `Thumbnail processing... (${retryCount + 1}/${MAX_RETRIES})`;
            setTimeout(() => {
                fetchThumbnail(key, imgElement, retryCount + 1);
            }, 2000);
        } else {
            imgElement.alt = "Thumbnail unavailable";
            console.error(`Failed to load thumbnail for ${key} after ${MAX_RETRIES} retries`);
        }
    });
}
```

---

### Question 10 (5 points)

**a) Bug if file has no extension (2 points)**

**Answer:**
- `key.split("\\.")[1]` will throw `ArrayIndexOutOfBoundsException`
- If filename is "README" (no dot), `split("\\.")` returns `["README"]`
- Accessing index `[1]` on a single-element array causes the crash
- The Lambda will return a 500 error

---

**b) What happens with multiple dots (1.5 points)**

**Answer:**
- For "photo.backup.jpg", `split("\\.")` returns `["photo", "backup", "jpg"]`
- `[1]` returns "backup" instead of "jpg"
- The MIME type will be set to "backup" which is invalid
- File may be served with wrong Content-Type header

---

**c) Corrected MIME type extraction logic (1.5 points)**

**Answer:**
```java
// Get extension from last dot
String mimeType = "application/octet-stream"; // default
int lastDotIndex = key.lastIndexOf('.');
if (lastDotIndex != -1 && lastDotIndex < key.length() - 1) {
    String extension = key.substring(lastDotIndex + 1).toLowerCase();
    switch (extension) {
        case "png":
            mimeType = "image/png";
            break;
        case "jpg":
        case "jpeg":
            mimeType = "image/jpeg";
            break;
        case "gif":
            mimeType = "image/gif";
            break;
        case "html":
            mimeType = "text/html";
            break;
        case "pdf":
            mimeType = "application/pdf";
            break;
        default:
            mimeType = "application/octet-stream";
    }
}
```

---

### Question 11 (5 points)

**a) What happens when response is double-encoded? (2 points)**

**Answer:**
- `LambdaGetPhotosDB` returns: `Base64(JSON_array)` = e.g., `"W3siaWQiOjF9XQ=="`
- `LambdaListObjectsOrchestrator` then encodes again: `Base64("W3siaWQiOjF9XQ==")` = `"VzN6aWFXUWlPakY5WFE9PQ=="`
- The result is a double-Base64-encoded string
- Frontend must decode twice to get the original JSON

---

**b) How does the frontend handle this? (1.5 points)**

**Answer:**
Looking at `fetchListOfObjects()`:
```javascript
.then((text) => {
    const decodedText = atob(text);  // First decode
    // ...
    renderListOfObjects(decodedText);  // decodedText is still Base64!
})
```

**Issue:** The frontend only decodes ONCE with `atob(text)`, but `renderListOfObjects` then calls `JSON.parse(listOfObjects)`.

**Actually**, examining more carefully:
- If `LambdaGetPhotosDB` returns base64-encoded JSON
- And `LambdaListObjectsOrchestrator` encodes it again
- Then `atob(text)` gives us the first base64 string
- But `JSON.parse()` would fail on a base64 string!

This appears to be handled because `LambdaListObjectsOrchestrator` extracts the `body` field from `LambdaGetPhotosDB`'s response, which is already the decoded content.

---

**c) Is this design intentional or a bug? (1.5 points)**

**Answer:**
**It's intentional** for Lambda Function URLs:

- Lambda Function URLs expect `isBase64Encoded: true` for binary or special content
- Each Lambda in the chain needs to return a properly formatted API Gateway response
- The orchestrator re-encodes because it's creating its own API Gateway response
- Without encoding, special characters in the response might get corrupted

**However**, a cleaner design would be:
- Only encode at the final output stage
- Pass raw data between Lambda functions internally
- This would simplify the code and reduce processing overhead

---

## Part D: Design & Architecture (15 points)

### Question 12 (8 points)

**a) Limitation of synchronous Lambda-to-Lambda invocations (2 points)**

**Answer:**
- **Timeout chain:** Each Lambda has a timeout (default 3s, max 15min). With 4-5 chained Lambdas, total execution time adds up
- **Concurrency limits:** AWS Lambda has default concurrent execution limit (1000). 1000 uploads × 5 Lambda calls = 5000 concurrent executions needed
- **Cost multiplication:** You pay for the orchestrator Lambda waiting while child Lambdas execute
- **Cold start accumulation:** Each Lambda in the chain may experience cold starts, compounding latency

---

**b) Alternative architecture using SQS or Step Functions (3 points)**

**Answer:**

**Using AWS Step Functions:**
```
                    ┌─────────────────────┐
                    │  Start (API Input)  │
                    └──────────┬──────────┘
                               │
                    ┌──────────▼──────────┐
                    │  ValidateToken      │
                    └──────────┬──────────┘
                               │
                    ┌──────────▼──────────┐
              ┌─────┤  Parallel State     ├─────┐
              │     └─────────────────────┘     │
    ┌─────────▼─────────┐           ┌──────────▼─────────┐
    │ Upload Original   │           │   Resize Image     │
    └─────────┬─────────┘           └──────────┬─────────┘
              │                                │
              └────────────┬───────────────────┘
                           │
                    ┌──────▼──────────────┐
              ┌─────┤  Parallel State     ├─────┐
              │     └─────────────────────┘     │
    ┌─────────▼─────────┐           ┌──────────▼─────────┐
    │ Upload Resized    │           │   Store in DB      │
    └─────────┬─────────┘           └──────────┬─────────┘
              │                                │
              └────────────┬───────────────────┘
                           │
                    ┌──────▼─────┐
                    │    End     │
                    └────────────┘
```

**Benefits:**
- Built-in retry and error handling
- Visual workflow monitoring
- Each Lambda runs independently (no timeout chain)
- Automatic state management

---

**c) What is Lambda cold start? Which functions are most affected? (3 points)**

**Answer:**

**Lambda Cold Start:** The initialization time when a new Lambda container is created. Includes:
1. Downloading the deployment package
2. Starting the runtime (JVM for Java)
3. Executing static initializers and constructors

**Most affected functions:**

1. **LambdaUploadDescriptionDB / LambdaGetPhotosDB / LambdaDeleteDescriptionDB**
   - Reason: VPC-attached Lambdas (for RDS access) have longer cold starts (can add 5-10 seconds)
   - JDBC driver initialization is slow

2. **LambdaImageResizer**
   - Reason: Java `BufferedImage` and AWT classes load slowly
   - Image processing libraries need initialization

3. **All Java Lambdas**
   - JVM startup is slower than Node.js or Python
   - Typical cold start: 3-10 seconds for Java vs 100-500ms for Python

**Mitigation:** Use Provisioned Concurrency or EventBridge scheduled warming (the project already has `EventBridgeInvoke` handling)

---

### Question 13 (7 points)

**a) More secure approach for bucket names (2 points)**

**Answer:**
Instead of hardcoding bucket names, use:

1. **Environment Variables:**
   ```java
   String bucketName = System.getenv("SOURCE_BUCKET_NAME");
   ```
   Configure in Lambda settings or CloudFormation/Terraform

2. **AWS Systems Manager Parameter Store:**
   ```java
   // Similar to how S3DownloadKey is retrieved
   HttpRequest request = HttpRequest.newBuilder()
       .uri(URI.create("http://localhost:2773/systemsmanager/parameters/get/?name=/gallery/buckets/source"))
       .build();
   ```

3. **AWS Secrets Manager** (for more sensitive configuration)

**Benefits:**
- Change bucket names without redeploying code
- Different values for dev/staging/production
- Audit trail of configuration changes

---

**b) Risks of unauthenticated thumbnail access (3 points)**

**Answer:**

**Risks:**
1. **Data exposure:** Anyone with the thumbnail URL can view all thumbnails
2. **Enumeration attack:** Attackers could try different `resized-` prefixes to discover images
3. **Bandwidth abuse:** No rate limiting; attackers could make millions of requests
4. **Privacy concern:** Even thumbnails may contain sensitive information

**Solution to require authentication:**

1. **Create ThumbnailOrchestrator:**
```java
public class LambdaThumbnailOrchestrator {
    public APIGatewayProxyResponseEvent handleRequest(...) {
        // 1. Validate token (same as other orchestrators)
        String tokenResponse = callLambda("LambdaTokenChecker", tokenWrapper, logger);
        if (!success) {
            return 403 response;
        }
        
        // 2. Call LambdaFetchThumbnails
        return callLambda("LambdaFetchThumbnails", payload, logger);
    }
}
```

2. **Update frontend:**
```javascript
function fetchThumbnail(key, imgElement) {
    const body = {
        "key": "resized-" + key,
        "email": document.getElementById('email').value,
        "token": document.getElementById('token').value
    };
    // Call new authenticated endpoint
}
```

---

**c) How generateAuthToken() works and why it's secure (2 points)**

**Answer:**

**How it works:**
```java
String authToken = rdsUtilities.generateAuthenticationToken(
    GenerateAuthenticationTokenRequest.builder()
        .hostname(RDS_INSTANCE_HOSTNAME)
        .port(RDS_INSTANCE_PORT)
        .username(DB_USER)
        .region(Region.US_EAST_1)
        .credentialsProvider(DefaultCredentialsProvider.create())
        .build());
```

1. Lambda's IAM role has permission to connect to RDS
2. AWS SDK creates a temporary token using IAM credentials
3. Token is valid for 15 minutes and contains a cryptographic signature
4. RDS validates the token against IAM

**Why it's more secure than static passwords:**
1. **No stored secrets:** No password in code, environment variables, or config files
2. **Automatic rotation:** Token expires in 15 minutes; no manual rotation needed
3. **IAM integration:** Access controlled by IAM policies; easy to revoke
4. **Audit trail:** All authentications logged in CloudTrail
5. **Principle of least privilege:** Lambda role only gets necessary RDS permissions

---

## Bonus Question (5 points)

### Question 14 - Photo Tagging System

**a) Database schema changes (2 points)**

**Answer:**
```sql
-- Tags table (stores unique tags)
CREATE TABLE Tags (
    TagID INT AUTO_INCREMENT PRIMARY KEY,
    TagName VARCHAR(50) NOT NULL UNIQUE,
    CreatedAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Junction table for many-to-many relationship
CREATE TABLE PhotoTags (
    PhotoID INT NOT NULL,
    TagID INT NOT NULL,
    PRIMARY KEY (PhotoID, TagID),
    FOREIGN KEY (PhotoID) REFERENCES Photos(ID) ON DELETE CASCADE,
    FOREIGN KEY (TagID) REFERENCES Tags(TagID) ON DELETE CASCADE
);

-- Index for faster lookups
CREATE INDEX idx_phototags_tagid ON PhotoTags(TagID);
```

---

**b) Lambda functions needed (1.5 points)**

**Answer:**

**New Lambda functions:**
1. `LambdaAddPhotoTags` - Add tags to a photo (INSERT into PhotoTags)
2. `LambdaRemovePhotoTag` - Remove a tag from a photo (DELETE from PhotoTags)
3. `LambdaGetPhotoTags` - Get all tags for a specific photo
4. `LambdaSearchByTag` - Search photos by tag name

**Modified Lambda functions:**
1. `LambdaGetPhotosDB` - Include tags in the response using JOIN:
   ```sql
   SELECT p.*, GROUP_CONCAT(t.TagName) as Tags 
   FROM Photos p 
   LEFT JOIN PhotoTags pt ON p.ID = pt.PhotoID 
   LEFT JOIN Tags t ON pt.TagID = t.TagID 
   GROUP BY p.ID
   ```

2. `LambdaUploadOrchestrator` - Optionally add tags during upload

3. `LambdaDeleteDescriptionDB` - Tags are auto-deleted via CASCADE

---

**c) Frontend UI changes (1.5 points)**

**Answer:**

**UI Changes:**
1. **Display tags:** Add a tags column/section in the gallery table
2. **Tag input field:** Add an input for tags during upload (comma-separated)
3. **Tag chips:** Display tags as clickable chips/badges
4. **Tag management:** Add/remove tags button for each photo
5. **Search by tag:** Filter gallery by clicking on a tag

**JavaScript functions:**
```javascript
// Display tags as chips
function renderTags(tagsString, photoKey) {
    if (!tagsString) return '';
    const tags = tagsString.split(',');
    return tags.map(tag => 
        `<span class="tag-chip" onclick="searchByTag('${tag}')">${tag}</span>`
    ).join(' ');
}

// Add tags to a photo
function addTagsToPhoto(photoKey) {
    const tags = prompt("Enter tags (comma-separated):");
    if (!tags) return;
    
    fetch(TAG_LAMBDA_URL, {
        method: 'POST',
        body: JSON.stringify({
            email: document.getElementById('email').value,
            token: document.getElementById('token').value,
            photoKey: photoKey,
            tags: tags.split(',').map(t => t.trim())
        })
    }).then(() => fetchListOfObjects());
}

// Search photos by tag
function searchByTag(tagName) {
    fetch(SEARCH_TAG_URL, {
        method: 'POST',
        body: JSON.stringify({
            email: document.getElementById('email').value,
            token: document.getElementById('token').value,
            tag: tagName
        })
    })
    .then(response => response.text())
    .then(text => {
        const decoded = atob(text);
        renderListOfObjects(decoded);
    });
}
```

**CSS for tag chips:**
```css
.tag-chip {
    display: inline-block;
    padding: 2px 8px;
    margin: 2px;
    background-color: #e0e0e0;
    border-radius: 12px;
    font-size: 12px;
    cursor: pointer;
}
.tag-chip:hover {
    background-color: #2196F3;
    color: white;
}
```

---

## End of Solutions

**Grading Notes:**
- Partial credit should be given for logical approaches even if syntax is not perfect
- Accept alternative solutions that achieve the same result
- For code questions, focus on understanding of concepts over exact syntax
