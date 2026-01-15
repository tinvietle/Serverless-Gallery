package vgu.cloud26;


import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONObject;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3EventNotificationRecord;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

public class LambdaDeleteResized implements RequestHandler<S3Event, String> {
        private static final float MAX_DIMENSION = 100;
        private final String REGEX = ".*\\.([^\\.]*)";
        private final String JPG_TYPE = "jpg";
        private final String JPEG_TYPE = "jpeg";
        private final String JPG_MIME = "image/jpeg";
        private final String PNG_TYPE = "png";
        private final String PNG_MIME = "image/png";

        @Override
        public String handleRequest(S3Event s3event, Context context) {
                

                LambdaLogger logger = context.getLogger();
                S3EventNotificationRecord record = s3event.getRecords().get(0);
                String srcBucket = "cloud-public-mpg";

                // Object key may have spaces or unicode non-ASCII characters.
                String srcKey = record.getS3().getObject().getUrlDecodedKey();

                // Check if this is a fake EventBridge trigger
                if ("EventBridgeInvoke".equals(srcKey)) {
                        logger.log("Invoked by EventBridge, no action taken.");
                        return "No action taken for EventBridge invocation.";
                }

                // String dstBucket = "lab-source-images-resized";
                String dstBucket = "resized-" + srcBucket;
                String dstKey = "resized-" + srcKey;

                // Infer the image type.
                Matcher matcher = Pattern.compile(REGEX).matcher(srcKey);
                if (!matcher.matches()) {
                        logger.log("Unable to infer image type for key " + srcKey);
                        return "";
                }
                String imageType = matcher.group(1);
                if (!(JPG_TYPE.equals(imageType)) && !(JPEG_TYPE.equals(imageType)) && !(PNG_TYPE.equals(imageType))) {
                        logger.log("Skipping non-image " + srcKey);
                        return "";
                }

                // Delete object from S3
                DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                                .bucket(dstBucket)
                                .key(dstKey)
                                .build();

                S3Client s3Client = S3Client.builder()
                                .region(Region.US_EAST_1)
                                .build();

                try {
                        s3Client.deleteObject(deleteObjectRequest);
                        context.getLogger().log(dstKey + " was deleted");
                } catch (Exception ex) {
                        throw new RuntimeException("An S3 exception occurred during delete", ex);
                }

                String message = "Object deleted successfully";
                return message;
        }

}
