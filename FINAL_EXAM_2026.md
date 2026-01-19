# Cloud Computing Final Exam - January 2026

**Course**: Cloud Computing  
**Duration**: 2 hours 30 minutes  
**Total Points**: 100 points  
**Open Book**: Students may bring printed code from Lambda functions and HTML files

---

## Instructions
- Answer all questions
- Write clearly and provide code snippets where requested
- Specify file paths when indicating where changes should be made
- Partial credit will be awarded for incomplete but logical answers

---

## Part A: System Architecture Understanding (25 points)

### Question 1 (5 points)
Examine the upload workflow in the webapp. When a user clicks the "Upload" button:

a) Which JavaScript function is triggered? (1 point)

b) What is the Lambda URL endpoint that receives the upload request? (1 point)

c) List all the data fields that are sent in the request body to the Lambda function. (3 points)

---

### Question 2 (8 points)
The `LambdaUploadOrchestrator` follows a specific workflow pattern. Based on your understanding:

a) In what order are the following Lambda functions invoked? (2 points)
   - `LambdaUploadObject`
   - `LambdaImageResizer`
   - `LambdaUploadDescriptionDB`
   - `LambdaTokenChecker`

b) Which operations run in parallel (concurrently) and which run sequentially? Explain why certain operations must be sequential. (3 points)

c) What is the naming convention used for generating unique filenames? Write the Java code pattern that generates this. (3 points)

---

### Question 3 (7 points)
Describe the delete workflow:

a) When a user clicks the "Delete" button in the gallery, which Lambda orchestrator is called? (1 point)

b) What are the three resources that need to be deleted when removing a photo? List them with their corresponding Lambda functions. (3 points)

c) The delete operations run in parallel using `CompletableFuture`. Why is it safe to run all three deletions concurrently? Would it cause any data inconsistency? Justify your answer. (3 points)

---

### Question 4 (5 points)
Token-based authentication:

a) Which algorithm is used for generating the authentication token? (1 point)

b) Where is the secret key stored and how does the Lambda function retrieve it? (2 points)

c) Why does the frontend store the token in an input field rather than using cookies or localStorage? What are the security implications? (2 points)

---

## Part B: Code Modification Tasks (45 points)

### Question 5 (12 points)
**Feature Request**: Add the ability to store and display the **upload timestamp** for each photo.

a) Which Lambda function needs to be modified to store the timestamp? What SQL statement changes are required? (3 points)

b) Which Lambda function retrieves photo metadata from the database? What changes are needed to include the timestamp in the response? (3 points)

c) In the `index.html` file, modify the `renderListOfObjects` function to display the upload timestamp in a new table column. Write the JavaScript code that needs to be added. (4 points)

d) What database schema change (ALTER TABLE statement) would be needed to support this feature? (2 points)

---

### Question 6 (10 points)
**Feature Request**: Add a "Search by Email" functionality to filter photos.

a) Create a new input field and button in `index.html` for searching by email. Write the HTML code. (2 points)

b) Write the JavaScript function `searchByEmail()` that will call a backend endpoint with the email parameter. Include proper error handling. (4 points)

c) Modify the `LambdaGetPhotosDB` Lambda function to accept an optional `email` filter parameter. Write the modified Java code for the SQL query section. (4 points)

---

### Question 7 (8 points)
**Feature Request**: Change the thumbnail size from 100px to 200px maximum dimension.

a) Which Lambda function(s) need to be modified? Specify the exact file path(s). (2 points)

b) What specific line(s) of code need to change? Write the before and after code. (3 points)

c) After deploying this change, will existing thumbnails be affected? Explain what would need to be done to update existing thumbnails. (3 points)

---

### Question 8 (15 points)
**Feature Request**: Implement a "Rename Photo Description" feature.

a) Create a new Lambda function called `LambdaUpdateDescriptionDB` that updates the description for a given S3Key. Write the complete Java handler method (you can use `LambdaUploadDescriptionDB` as a reference). (6 points)

b) Add a new JavaScript function `updateDescription(key)` in `index.html` that:
   - Prompts the user for a new description
   - Sends the update request to a Lambda endpoint
   - Refreshes the gallery after successful update
   Write the complete function. (5 points)

c) Modify the `renderListOfObjects` function to add an "Edit" button next to each photo that calls `updateDescription()`. Write the code for adding this button. (4 points)

---

## Part C: Debugging & Troubleshooting (15 points)

### Question 9 (5 points)
A user reports that thumbnails are showing "Loading..." indefinitely. Based on the code in `index.html`:

a) What is the retry mechanism implemented in `fetchThumbnail()`? (2 points)

b) Identify a potential issue if the S3 resized bucket is not properly configured. What would happen? (1.5 points)

c) How would you add a maximum retry limit to prevent infinite retries? Write the modified code. (1.5 points)

---

### Question 10 (5 points)
Examine the following code from `LambdaDownloadObject`:

```java
for (S3Object object : objects) {
    if (object.key().equals(key)) {
        found = true;
        int objectSize = Math.toIntExact(object.size());
        if (objectSize < maxSize){
            validSize = true ;
        }
        mimeType = key.split("\\.")[1];
        // ...
    }
}
```

a) What is the bug if a file has no extension (e.g., "README")? (2 points)

b) What happens if a file has multiple dots in the name (e.g., "photo.backup.jpg")? (1.5 points)

c) Write a corrected version of the MIME type extraction logic. (1.5 points)

---

### Question 11 (5 points)
The `LambdaListObjectsOrchestrator` double-encodes the response. Trace through the code:

a) The response from `LambdaGetPhotosDB` is already Base64 encoded. What happens when `LambdaListObjectsOrchestrator` encodes it again? (2 points)

b) Looking at the `fetchListOfObjects()` function in `index.html`, how does the frontend handle this? (1.5 points)

c) Is this design choice (double encoding) intentional or a bug? Justify your answer based on how Lambda function URLs handle responses. (1.5 points)

---

## Part D: Design & Architecture (15 points)

### Question 12 (8 points)
**Scalability Analysis**:

a) The current architecture uses synchronous Lambda-to-Lambda invocations. Describe one limitation of this approach when handling 1000 concurrent uploads. (2 points)

b) Propose an alternative architecture using AWS SQS or AWS Step Functions. Draw or describe the workflow. (3 points)

c) What is "Lambda cold start"? Which Lambda functions in this project would be most affected by cold starts and why? (3 points)

---

### Question 13 (7 points)
**Security Improvements**:

a) The current implementation stores bucket names as hardcoded strings in Lambda functions. Propose a more secure approach using AWS services. (2 points)

b) The `LambdaFetchThumbnails` function does not require authentication. What risks does this pose? How would you modify the system to require authentication for thumbnails? (3 points)

c) The RDS connection uses IAM authentication. Explain how `generateAuthToken()` works and why this is more secure than storing a static password. (2 points)

---

## Bonus Question (5 points)

### Question 14 (5 points)
**Advanced Feature**: Implement a photo "tagging" system where users can add multiple tags to each photo.

a) Design the database schema changes needed (consider using a separate Tags table with a many-to-many relationship). (2 points)

b) Describe what Lambda functions would need to be created or modified. (1.5 points)

c) How would you modify the frontend to display and edit tags? Describe the UI changes and JavaScript functions needed. (1.5 points)

---

## End of Exam

Good luck!
