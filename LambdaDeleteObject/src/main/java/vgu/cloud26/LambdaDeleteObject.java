/*
function LambdaDeleteObject
Description: Delete object from given S3 bucket
*/

package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.util.Base64;
import org.json.JSONObject;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;


public class LambdaDeleteObject implements
        RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    @Override
    public APIGatewayProxyResponseEvent
            handleRequest(APIGatewayProxyRequestEvent event, Context context) {
       
        // String bucketName = "cloud-public-mpg";
                        
        // Parse request body to get the object key
        String requestBody = event.getBody();
        if (requestBody != null && requestBody.equals("EventBridgeInvoke")) {
            context.getLogger().log("Invoked by EventBridge, no action taken.");
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withBody("No action taken for EventBridge invocation.");
        }
        JSONObject bodyJSON = new JSONObject(requestBody);
        String objName = bodyJSON.getString("key");
        String bucketName = bodyJSON.getString("bucket");
        
        // Delete object from S3
        DeleteObjectRequest deleteObjectRequest = 
                DeleteObjectRequest.builder()
                        .bucket(bucketName)
                        .key(objName)
                        .build();

        S3Client s3Client = S3Client.builder()
                        .region(Region.US_EAST_1)
                        .build();
        
        try {
                s3Client.deleteObject(deleteObjectRequest);
                context.getLogger().log(objName + " was deleted");
        } catch (Exception ex) {
                throw new RuntimeException("An S3 exception occurred during delete", ex);
        }
        
        String message = "Object deleted successfully";
        String encodedString = Base64.getEncoder().encodeToString(message.getBytes());

        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setStatusCode(200);
        response.setBody(encodedString);
        response.withIsBase64Encoded(true);
        response.setHeaders(java.util.Collections.singletonMap("Content-Type", "text/plain"));
        
        return response;
    }

}

