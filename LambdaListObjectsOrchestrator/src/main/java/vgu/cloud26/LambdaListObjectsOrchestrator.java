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
import org.json.JSONObject;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.SdkBytes;            
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

public class LambdaListObjectsOrchestrator implements
        RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final LambdaClient lambdaClient;

    public LambdaListObjectsOrchestrator() {
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
        if (requestBody != null && requestBody.equals("EventBridgeInvoke")) {
            logger.log("Invoked by EventBridge, no action taken.");
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withBody("No action taken for EventBridge invocation.");
        }
        JSONObject bodyJSON = new JSONObject(requestBody);


        String email = bodyJSON.getString("email");
        String token = bodyJSON.getString("token");

        String responseString = "";

        // 1. Invoke LambdaTokenChecker
        JSONObject tokenPayload = new JSONObject()
                .put("email", email)
                .put("token", token);

        JSONObject tokenWrapper = new JSONObject()
                .put("body", tokenPayload.toString());

        responseString = callLambda("LambdaTokenChecker", tokenWrapper.toString(), logger);

        // 2. Check if Email and Token are valid
        JSONObject tokenResponse = new JSONObject(responseString);
        Boolean success = tokenResponse.optBoolean("success", false);
        if (!success) {
            logger.log("Token validation failed. Aborting download.");
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(403)
                    .withBody("Invalid token. Access denied.")
                    .withIsBase64Encoded(false)
                    .withHeaders(Map.of("Content-Type", "text/plain"));
        }

        // 3. Invoke LambdaGetListOfObjects
        String payload = "";
        responseString = callLambda("LambdaGetPhotosDB", payload, logger);

        // 4. Return the downloaded object (base64 encoded)
        // Base64 encode final combined response
        String encodedString = Base64.getEncoder().encodeToString(responseString.getBytes());

        return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withBody(encodedString)
                .withIsBase64Encoded(true)
                .withHeaders(Map.of("Content-Type", "text/plain"));
    }
}
