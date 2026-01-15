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

public class LambdaUploadDescriptionDB
                implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

        private static final String RDS_INSTANCE_HOSTNAME

                        = "database-1.c6p4im2uqehz.us-east-1.rds.amazonaws.com";

        private static final int RDS_INSTANCE_PORT = 3306;

        private static final String DB_USER = "cloud26";

        private static final String JDBC_URL

                        = "jdbc:mysql://" + RDS_INSTANCE_HOSTNAME

                                        + ":" + RDS_INSTANCE_PORT + "/Cloud26";

        @Override

        public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
                LambdaLogger logger = context.getLogger();

                try {
                        String requestBody = request.getBody();
                        
                        // Check if invoked by EventBridge (body is just a string, not JSON)
                        if (requestBody != null && requestBody.equals("EventBridgeInvoke")) {
                                logger.log("Invoked by EventBridge, no action taken.");
                                return new APIGatewayProxyResponseEvent()
                                                .withStatusCode(200)
                                                .withBody("No action taken for EventBridge invocation.");
                        }
                        
                        JSONObject json = new JSONObject(requestBody);
                        String description = json.getString("description");
                        String imageKey = json.getString("imageKey");

                        Class.forName("com.mysql.cj.jdbc.Driver");

                        try (Connection conn = DriverManager.getConnection(JDBC_URL, setMySqlConnectionProperties());
                                        PreparedStatement stmt = conn.prepareStatement(
                                                        "INSERT INTO Photos (Description, S3Key) VALUES (?, ?)")) {
                                stmt.setString(1, description);
                                stmt.setString(2, imageKey);
                                stmt.executeUpdate();
                        }

                } catch (Exception ex) {
                        logger.log("Error: " + ex.getMessage());

                        return new APIGatewayProxyResponseEvent()
                                        .withStatusCode(500)
                                        .withBody("{\"message\":\"Error uploading description\"}")
                                        .withHeaders(Collections.singletonMap("Content-Type", "application/json"));
                }

                // Only Base64 encode if API Gateway expects it; otherwise remove this.
                String encodedResult = Base64.getEncoder().encodeToString("Upload description success".getBytes());

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

                mysqlConnectionProperties.setProperty("password", generateAuthToken()); // HERE

                return mysqlConnectionProperties;

        }

        private static String generateAuthToken() throws Exception {

                RdsUtilities rdsUtilities = RdsUtilities.builder().build();

                // Generate the authentication token

                String authToken

                                = rdsUtilities.generateAuthenticationToken(

                                                GenerateAuthenticationTokenRequest.builder()

                                                                .hostname(RDS_INSTANCE_HOSTNAME)

                                                                .port(RDS_INSTANCE_PORT)

                                                                .username(DB_USER)

                                                                .region(Region.US_EAST_1)

                                                                .credentialsProvider(
                                                                                DefaultCredentialsProvider.create())

                                                                .build());

                return authToken;

        }

}