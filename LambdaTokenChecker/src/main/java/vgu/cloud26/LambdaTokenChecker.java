/*

*/

package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

public class LambdaTokenChecker implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    public static String generateSecureToken(String data, String key, LambdaLogger logger) {
        try {

            // Specify the HMAC-SHA256 algorithm
            Mac mac = Mac.getInstance("HmacSHA256");

            // Create a SecretKeySpec from the provided key
            SecretKeySpec secretKeySpec
                    = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),
                            "HmacSHA256");
            // Initialize the Mac with the secret key
            mac.init(secretKeySpec);

            // Compute the HMAC-SHA256 hash of the data
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));

            // Convert the byte array to a base64 string 
            String base64 = Base64.getEncoder().encodeToString(hmacBytes);

            logger.log("Input String: " + data);
            logger.log("Secure Token: " + base64);

            return base64;

        } catch (NoSuchAlgorithmException e) {
            // Handle the exception (e.g., log it, throw a custom exception)
            logger.log("HmacSHA256 algorithm not found: " + e.getMessage());
            return null;
        } catch (InvalidKeyException ex) {
            logger.log("InvalidKeyException: " + ex.getMessage());
            return null;
        }
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        LambdaLogger logger = context.getLogger();
        logger.log("Processing token checking request");

        try {
            // Parse the request body
            String requestBody = event.getBody();
            if (requestBody != null && requestBody.equals("EventBridgeInvoke")) {
                logger.log("Invoked by EventBridge, no action taken.");
                return new APIGatewayProxyResponseEvent()
                                .withStatusCode(200)
                                .withBody("No action taken for EventBridge invocation.");
            }
            JSONObject json = new JSONObject(requestBody);
            
            String email = json.getString("email");
            String token = json.getString("token");
            String key = "cloud26";
            
            // Generate the token
            String generatedToken = generateSecureToken(email, key, logger);

            // Check if the generated token matches the provided token
            JSONObject responseBody = new JSONObject();
            if (generatedToken != null && generatedToken.equals(token)){
                responseBody.put("success", true);
            } else {
                responseBody.put("success", false);
                responseBody.put("error", "Invalid token");
            }          
            
            Map<String, String> headers = Map.of(
                "Content-Type", "application/json"
            );
            
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withHeaders(headers)
                .withBody(responseBody.toString());
                
        } catch (Exception e) {
            logger.log("Error processing request: " + e.getMessage());
            
            JSONObject errorResponse = new JSONObject();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(500)
                .withBody(errorResponse.toString());
        }
    }
}
