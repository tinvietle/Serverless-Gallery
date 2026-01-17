/*
Function: LambdaFetchThumbnails
Description: Retrieved the corresponding thumnail (resized image) from resized bucket and transfer through network as base64 encoded string.
*/

package vgu.cloud26;


import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import org.json.JSONObject;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsResponse;
import software.amazon.awssdk.services.s3.model.S3Object;


public class LambdaFetchThumbnails implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {

        String requestBody = request.getBody();

        if (requestBody != null && requestBody.equals("EventBridgeInvoke")) {
            context.getLogger().log("Invoked by EventBridge, no action taken.");
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withBody("No action taken for EventBridge invocation.");
        }

        JSONObject bodyJSON = new JSONObject(requestBody);
        String key = bodyJSON.getString("key");
        //Map<String, String> params = request.getQueryStringParameters();
        //String key = params.get("key");

        String bucketName = "resized-cloud-public-mpg";
        S3Client s3Client = S3Client.builder()
                .region(Region.US_EAST_1)
                .build();

        ListObjectsRequest listObjects = ListObjectsRequest
                .builder()
                .bucket(bucketName)
                .build();

        ListObjectsResponse res = s3Client.listObjects(listObjects);
        List<S3Object> objects = res.contents();
        
         // 1 MB is equal to 1024 kilobytes (KB), and 1 KB is equal to 1024 bytes.
        int maxSize = 10 * 1024 * 1024;
        Boolean found = false;
        Boolean validSize = false;
        String mimeType = "application/octet-stream";

        // Check if the object exists and its size
        for (S3Object object : objects) {
            if (object.key().equals(key)) {
                found = true;
                int objectSize = Math.toIntExact(object.size());
                if (objectSize < maxSize){
                    validSize = true ;
                }
                mimeType = key.split("\\.")[1];
                if (mimeType.equals("png")){
                    mimeType = "image/png";
                } else if (mimeType.equals("html")){
                    mimeType = "text/html";
                }
                break;
            }
        }
        String encodedString = "";
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        
        if (!found) {
            // Thumbnail not found - return 404
            response.setStatusCode(404);
            response.setBody("{\"error\": \"Thumbnail not found\"}");
            response.setHeaders(java.util.Collections.singletonMap("Content-Type", "application/json"));
            return response;
        }
        
        if (!validSize) {
            // File too large - return 413
            response.setStatusCode(413);
            response.setBody("{\"error\": \"File too large\"}");
            response.setHeaders(java.util.Collections.singletonMap("Content-Type", "application/json"));
            return response;
        }
        
        // File found and valid size
        GetObjectRequest s3Request = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();
        
        byte[] buffer = new byte[10 * 1024 * 1024];
        try (ResponseInputStream<GetObjectResponse> s3Response = s3Client.getObject(s3Request)) {
            buffer = s3Response.readAllBytes();
        } catch (IOException ex) {
            context.getLogger().log("IOException: " + ex);
            response.setStatusCode(500);
            response.setBody("{\"error\": \"Failed to read object\"}");
            response.setHeaders(java.util.Collections.singletonMap("Content-Type", "application/json"));
            return response;
        }

        encodedString = Base64.getEncoder().encodeToString(buffer);
        
        response.setStatusCode(200);
        response.setBody(encodedString);
        response.withIsBase64Encoded(true);
        response.setHeaders(java.util.Collections.singletonMap("Content-Type", mimeType));
        return response;
    }

}
