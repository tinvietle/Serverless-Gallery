package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.json.JSONObject;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.SdkBytes;            
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

public class LambdaUploadOrchestrator implements
        RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final LambdaClient lambdaClient;

    public LambdaUploadOrchestrator() {
        this.lambdaClient = LambdaClient.builder()
                .region(Region.of("us-east-1"))
                .build();
    }

    // Helper to call another Lambda
    public String callLambda(String functionName, String payload, LambdaLogger logger) {
        String message;
        InvokeRequest invokeRequest = InvokeRequest.builder()
                .functionName(functionName)
                .invocationType("RequestResponse")
                .payload(SdkBytes.fromUtf8String(payload))
                .build();

        try {
            InvokeResponse invokeResult = lambdaClient.invoke(invokeRequest);
            ByteBuffer responsePayload = invokeResult.payload().asByteBuffer();
            String responseString = StandardCharsets.UTF_8.decode(responsePayload).toString();

            JSONObject responseObject = new JSONObject(responseString);
            message = responseObject.optString("body", "");
            logger.log("Response from " + functionName + ": " + message);
            return message;

        } catch (AwsServiceException | SdkClientException e) {
            message = "Error calling " + functionName + ": " + e.getMessage();
            logger.log(message);
            return message;
        }
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        LambdaLogger logger = context.getLogger();

        String requestBody = event.getBody();
        if (requestBody == "EventBridgeInvoke") {
            logger.log("Invoked by EventBridge, no action taken.");
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withBody("No action taken for EventBridge invocation.");
        }
        JSONObject bodyJSON = new JSONObject(requestBody);


        String content = bodyJSON.getString("content");
        String objName = bodyJSON.getString("key");
        String objDescription = bodyJSON.getString("description");

        String ext = objName.substring(objName.lastIndexOf('.'));
        String uniqueFilename = System.currentTimeMillis() + "_" + UUID.randomUUID().toString() + ext;

        String responseString = "";

        // 1. Invoke LambdaUploadObject (upload image)
        JSONObject filePayload = new JSONObject()
                .put("content", content)
                .put("key", uniqueFilename)
                .put("bucket", "cloud-public-mpg");

        JSONObject fileWrapper = new JSONObject()
                .put("body", filePayload.toString());

        responseString += callLambda("LambdaUploadObject", fileWrapper.toString(), logger);


        // 2. Invoke LambdaImageResizer (resize image)
        JSONObject resizePayload = new JSONObject()
                .put("content", content);

        JSONObject resizeWrapper = new JSONObject()
                .put("body", resizePayload.toString());

        String resizeResponse = callLambda("LambdaImageResizer", resizeWrapper.toString(), logger);
        
        // 3. Upload resized image back to S3
        String resizedKey = "resized-" + uniqueFilename;
        JSONObject resizeImagePayload = new JSONObject()
                .put("content", resizeResponse)
                .put("key", resizedKey)
                .put("bucket", "resized-cloud-public-mpg");

        JSONObject resizeImageWrapper = new JSONObject()
                .put("body", resizeImagePayload.toString());

        responseString += callLambda("LambdaUploadObject", resizeImageWrapper.toString(), logger);

        // 4. Invoke LambdaUploadDescriptionDB
        JSONObject descPayload = new JSONObject()
                .put("imageKey", uniqueFilename)
                .put("description", objDescription);

        JSONObject descWrapper = new JSONObject()
                .put("body", descPayload.toString());

        responseString += callLambda("LambdaUploadDescriptionDB", descWrapper.toString(), logger);

        // Base64 encode final combined response
        String encodedString = Base64.getEncoder().encodeToString(responseString.getBytes());

        return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withBody(encodedString)
                .withIsBase64Encoded(true)
                .withHeaders(Map.of("Content-Type", "text/plain"));
    }
}
