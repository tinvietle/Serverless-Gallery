/*
function LambdaDeleteOrchestrator
Description: Invoke deleteion of object in S3, resized S3, and description in DB. Handle token validation beforehand.
*/

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.json.JSONObject;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

public class LambdaDeleteOrchestrator implements
                RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

        private final LambdaClient lambdaClient;

        public LambdaDeleteOrchestrator() {
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

        // Helper to call another Lambda asynchronously
        public CompletableFuture<String> callLambdaAsync(String functionName, String payload, LambdaLogger logger) {
                return CompletableFuture.supplyAsync(() -> {
                        return callLambda(functionName, payload, logger);
                });
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
                String objName = bodyJSON.getString("key");
                String responseString = "";

                // PARALLEL PROCESSING: Delete original image and description DB concurrently
                // Note: The resized image will be deleted automatically by S3 event trigger to
                // LambdaDeleteResized
                try {
                        // Step 0: Validate token first
                        JSONObject tokenPayload = new JSONObject()
                                        .put("email", email)
                                        .put("token", token);
                        JSONObject tokenWrapper = new JSONObject()
                                        .put("body", tokenPayload.toString());
                        String tokenResponse = callLambda("LambdaTokenChecker", tokenWrapper.toString(), logger);
                        JSONObject tokenResponseJSON = new JSONObject(tokenResponse);
                        boolean success = tokenResponseJSON.optBoolean("success", false);
                        if (!success) {
                                logger.log("Token validation failed. Aborting upload.");
                                return new APIGatewayProxyResponseEvent()
                                                .withStatusCode(403)
                                                .withBody("Invalid token. Access denied.")
                                                .withIsBase64Encoded(false)
                                                .withHeaders(Map.of("Content-Type", "text/plain"));
                        }

                        // 1. Delete original object from S3
                        JSONObject deletePayload = new JSONObject()
                                        .put("key", objName)
                                        .put("bucket", "cloud-public-mpg");
                        JSONObject deleteWrapper = new JSONObject()
                                        .put("body", deletePayload.toString());                      

                        // 2. Delete description from DB - can run in parallel
                        JSONObject deleteDescPayload = new JSONObject()
                                        .put("imageKey", objName);
                        JSONObject deleteDescWrapper = new JSONObject()
                                        .put("body", deleteDescPayload.toString());

                        // 3. Delete resized image from S3 - can run in parallel
                        String resizedKey = "resized-" + objName;
                        JSONObject deleteResizedPayload = new JSONObject()
                                        .put("key", resizedKey)
                                        .put("bucket", "resized-cloud-public-mpg");
                        JSONObject deleteResizedWrapper = new JSONObject()
                                        .put("body", deleteResizedPayload.toString());

                        // Invoke Lambdas asynchronously
                        CompletableFuture<String> deleteObjectFuture = callLambdaAsync("LambdaDeleteObject",
                                        deleteWrapper.toString(), logger);
                        CompletableFuture<String> deleteDescFuture = callLambdaAsync("LambdaDeleteDescriptionDB",
                                        deleteDescWrapper.toString(), logger);
                        CompletableFuture<String> deleteResizedFuture = callLambdaAsync("LambdaDeleteObject",
                                        deleteResizedWrapper.toString(), logger);

                        // Wait for both to complete
                        String deleteObjectResponse = deleteObjectFuture.get();
                        String deleteDescResponse = deleteDescFuture.get();
                        String deleteResizedResponse = deleteResizedFuture.get();

                        responseString = deleteObjectResponse + deleteDescResponse + deleteResizedResponse;

                } catch (InterruptedException | ExecutionException e) {
                        logger.log("Error during parallel execution: " + e.getMessage());
                        responseString = "Error: " + e.getMessage();
                }

                /*
                 * SEQUENTIAL PROCESSING (FOR COMPARISON):
                 * // 1. Delete original object from S3
                 * JSONObject deletePayload = new JSONObject()
                 * .put("key", objName);
                 * JSONObject deleteWrapper = new JSONObject()
                 * .put("body", deletePayload.toString());
                 * 
                 * responseString += callLambda("LambdaDeleteObject", deleteWrapper.toString(),
                 * logger);
                 * 
                 * // 2. Delete description from DB
                 * JSONObject deleteDescPayload = new JSONObject()
                 * .put("imageKey", objName);
                 * JSONObject deleteDescWrapper = new JSONObject()
                 * .put("body", deleteDescPayload.toString());
                 * 
                 * responseString += callLambda("LambdaDeleteDescriptionDB",
                 * deleteDescWrapper.toString(), logger);
                 * 
                 * // 3. Delete resized image from S3
                 * String resizedKey = "resized-" + objName;
                 * JSONObject deleteResizedPayload = new JSONObject()
                 * .put("key", resizedKey);
                 * JSONObject deleteResizedWrapper = new JSONObject()
                 * .put("body", deleteResizedPayload.toString());
                 * 
                 * responseString += callLambda("LambdaDeleteResized",
                 * deleteResizedWrapper.toString(), logger);
                 */

                // Base64 encode final combined response
                String encodedString = Base64.getEncoder().encodeToString(responseString.getBytes());

                return new APIGatewayProxyResponseEvent()
                                .withStatusCode(200)
                                .withBody(encodedString)
                                .withIsBase64Encoded(true)
                                .withHeaders(Map.of("Content-Type", "text/plain"));
        }
}
