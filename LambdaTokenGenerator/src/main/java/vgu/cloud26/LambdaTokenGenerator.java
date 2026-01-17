/*
Function: LambdaTokenGenerator
Description: Generate secure token using HMAC-SHA256 based on email and secret key from Parameter Store.
*/

package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

public class LambdaTokenGenerator implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

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
        logger.log("Processing token generation request");

        try {
            // Parse the request body
            String requestBody = event.getBody();
            if (requestBody != null && requestBody.equals("EventBridgeInvoke")) {
                logger.log("Invoked by EventBridge, no action taken.");
                return new APIGatewayProxyResponseEvent()
                        .withStatusCode(200)
                        .withBody("");
            }
            JSONObject json = new JSONObject(requestBody);
            
            String email = json.getString("email");

            // Check if email is provided
            if (email == null || email.isEmpty()) {
                logger.log("Email is missing in the request");
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(400)
                    .withBody("{\"success\": false, \"error\": \"Email is required\"}");
            }
            
            // Get the session token from environment variable
            String sessionToken = System.getenv("AWS_SESSION_TOKEN");
            
            HttpClient client = HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();
                
                HttpRequest requestParameter = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:2773/systemsmanager/parameters/get/?name=S3DownloadKey&withDecryption=true"))
                        .header("X-Aws-Parameters-Secrets-Token", sessionToken)
                        .GET()
                        .build();

                HttpResponse<String> responseParameter = client.send(requestParameter, HttpResponse.BodyHandlers.ofString());

                // Parse the JSON response to extract the Parameter value
                JSONObject paramResponse = new JSONObject(responseParameter.body());
                JSONObject parameter = paramResponse.getJSONObject("Parameter");
                String key = parameter.getString("Value");
            
            logger.log("Using key from parameter store: " + key);
            
            // Generate the token
            String token = generateSecureToken(email, key, logger);
            
            JSONObject responseBody = new JSONObject();
            responseBody.put("token", token);   
            
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
