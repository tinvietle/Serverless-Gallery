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

public class LambdaDeleteDescriptionDB
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

                String requestBody = request.getBody();
                if (requestBody != null && requestBody.equals("EventBridgeInvoke")) {
                        logger.log("Invoked by EventBridge, no action taken.");
                        return new APIGatewayProxyResponseEvent()
                                        .withStatusCode(200)
                                        .withBody("No action taken for EventBridge invocation.");
                }

                try {
                        JSONObject json = new JSONObject(requestBody);
                        String imageKey = json.getString("imageKey");

                        Class.forName("com.mysql.cj.jdbc.Driver");

                        try (Connection conn = DriverManager.getConnection(JDBC_URL, setMySqlConnectionProperties());
                                        PreparedStatement stmt = conn.prepareStatement(
                                                        "DELETE FROM Photos WHERE S3Key = ?")) {
                                stmt.setString(1, imageKey);
                                int rowsAffected = stmt.executeUpdate();
                                logger.log("Deleted " + rowsAffected + " row(s) for key: " + imageKey);
                        }

                } catch (Exception ex) {
                        logger.log("Error: " + ex.getMessage());

                        return new APIGatewayProxyResponseEvent()
                                        .withStatusCode(500)
                                        .withBody("{\"message\":\"Error deleting description\"}")
                                        .withHeaders(Collections.singletonMap("Content-Type", "application/json"));
                }

                String encodedResult = Base64.getEncoder().encodeToString("Delete description success".getBytes());

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

                mysqlConnectionProperties.setProperty("password", generateAuthToken());

                return mysqlConnectionProperties;

        }

        private static String generateAuthToken() throws Exception {

                RdsUtilities rdsUtilities = RdsUtilities.builder().build();

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
