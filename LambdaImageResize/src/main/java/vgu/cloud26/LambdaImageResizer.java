/*
Function: LambdaImageResizer
Description: Take input image as base64 string, resize it to thumbnail size, and return reiszed image as base64 string.
*/

package vgu.cloud26;


import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Base64;
import java.util.Map;

import javax.imageio.ImageIO;

// import software.amazon.awssdk.services.s3.model.GetObjectRequest;
// import software.amazon.awssdk.services.s3.S3Client;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.json.JSONObject;


import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;

;

// Handler value: example.Handler
public class LambdaImageResizer implements
        RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final float MAX_DIMENSION = 100;

    @Override
    public APIGatewayProxyResponseEvent
            handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        LambdaLogger logger = context.getLogger();

        String content = event.getBody();
        if (content != null && content.equals("EventBridgeInvoke")) {
            logger.log("Invoked by EventBridge, no action taken.");
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withBody("No action taken for EventBridge invocation.");
        }

        try {
            // Parse request
            JSONObject bodyJSON = new JSONObject(event.getBody());
            // String srcKey = bodyJSON.getString("key");
            // String srcBucket = bodyJSON.getString("bucket");
            String base64Image = bodyJSON.getString("content");

            byte[] imageBytes = Base64.getDecoder().decode(base64Image.getBytes());

            // Resize image
            InputStream imageInputStream = new java.io.ByteArrayInputStream(imageBytes);
            BufferedImage srcImage = ImageIO.read(imageInputStream);
            BufferedImage resized = resizeImage(srcImage);

            // Encode as base64
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            ImageIO.write(resized, "jpeg", outputStream);
            String encoded = Base64.getEncoder().encodeToString(outputStream.toByteArray());

            // Build response
            APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withIsBase64Encoded(true)
                    .withBody(encoded)
                    .withHeaders(Map.of(
                            "Content-Type", "image/jpeg"
                    ));

            return response;

        } catch (Exception e) {
            logger.log("Error: " + e.getMessage());

            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withBody("{\"error\": \"Upload failed\"}")
                    .withHeaders(Map.of(
                            "Content-Type", "application/json"
                    ));
        }
    }

//     private String getFileExtension(String key) throws Exception {
//         int lastDot = key.lastIndexOf('.');
//         if (lastDot == -1 || lastDot == key.length() - 1) {
//             return "jpg";
//         }
//         return key.substring(lastDot + 1).toLowerCase();
//     }

//     private InputStream getObject(S3Client s3Client, String bucket, String key) {
//         GetObjectRequest getObjectRequest = GetObjectRequest.builder()
//                 .bucket(bucket)
//                 .key(key)
//                 .build();
//         return s3Client.getObject(getObjectRequest);
//     }

    /**
     * Resizes (shrinks) an image into a small, thumbnail-sized image.
     *
     * The new image is scaled down proportionally based on the source image.
     * The scaling factor is determined based on the value of MAX_DIMENSION. The
     * resulting new image has max(height, width) = MAX_DIMENSION.
     *
     * @param srcImage BufferedImage to resize.
     * @return New BufferedImage that is scaled down to thumbnail size.
     */
    private BufferedImage resizeImage(BufferedImage srcImage) {
        int srcHeight = srcImage.getHeight();
        int srcWidth = srcImage.getWidth();
        // Infer scaling factor to avoid stretching image unnaturally
        float scalingFactor = Math.min(
                MAX_DIMENSION / srcWidth, MAX_DIMENSION / srcHeight);
        int width = (int) (scalingFactor * srcWidth);
        int height = (int) (scalingFactor * srcHeight);

        BufferedImage resizedImage = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resizedImage.createGraphics();
        // Fill with white before applying semi-transparent (alpha) images
        graphics.setPaint(Color.white);
        graphics.fillRect(0, 0, width, height);
        // Simple bilinear resize
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(srcImage, 0, 0, width, height, null);
        graphics.dispose();
        return resizedImage;
    }
}
