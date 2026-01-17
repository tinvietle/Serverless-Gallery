/*
Function: LambdaUploadObject
Description: Upload object to specified S3 bucket given base64 content and key.
*/

package vgu.cloud26;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.util.Base64;
import org.json.JSONObject;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;


public class LambdaUploadObject implements
        RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    @Override
    public APIGatewayProxyResponseEvent
            handleRequest(APIGatewayProxyRequestEvent event, Context context) {
       
        // String bucketName = "cloud-public-mpg";
        String requestBody = event.getBody();

        if (requestBody != null && requestBody.equals("EventBridgeInvoke")) {
            context.getLogger().log("Invoked by EventBridge, no action taken.");
            return new APIGatewayProxyResponseEvent()
                            .withStatusCode(200)
                            .withBody("No action taken for EventBridge invocation.");
        }
        
        // Parse request body to get the object content, key, and bucket name
        JSONObject bodyJSON = new JSONObject(requestBody);
        String content = bodyJSON.getString("content");
        String objName = bodyJSON.getString("key");
        String bucketName = bodyJSON.getString("bucket");
        
        
        byte[] objBytes = Base64.getDecoder().decode(content.getBytes());
        
        // Create PutObjectRequest
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(objName)
                .build();

        // Upload object to S3
        S3Client s3Client = S3Client.builder()
                .region(Region.US_EAST_1)
                .build();
        s3Client.putObject(putObjectRequest,
                RequestBody.fromBytes(objBytes));

        
        String message = "Object uploaded successfully";

        String encodedString = Base64.getEncoder().encodeToString(message.getBytes());

        APIGatewayProxyResponseEvent response;
        response = new APIGatewayProxyResponseEvent();
        response.setStatusCode(200);
        response.setBody(encodedString);
        response.withIsBase64Encoded(true);
        response.setHeaders(java.util.Collections.singletonMap("Content-Type", "text/plain"));
        
        return response;
    }

}

