package vgu.cloud26;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher; 
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;


/* Input - <Map<String, Object>>
AWS automatically converts JSON payload received from Orchestrator to a Map (key-value pairs)
String - are default labels : key, content
Object - are the values coresponding to each label :
        Example
    key : goat.png
    content : a base64encodedstring

Output - <String> 
Return a simple JSON string indicating the result of success/failure
*/
public class LambdaResizer implements RequestHandler<Map<String, Object>, String> {

    private static final float MAX_DIMENSION = 100;
    // Regex pattern to extract the file extension from the filename (Key)
    // Example: "image.test.jpg" -> extracts "jpg"
    private final String REGEX = ".*\\.([^\\.]*)";

    // 1. FILE TYPE (Extension): Used for internal Java logic and validation
    // Example: Checking if file extension matches "jpg" or "png"
    // 2. MIME TYPE (Content-Type): Used for HTTP/Web standards
    // Example: Setting S3 Metadata so browsers render the image instead of downloading it.
    private final String JPG_TYPE = "jpg";
    private final String JPG_MIME = "image/jpeg";
    private final String PNG_TYPE = "png";
    private final String PNG_MIME = "image/png";


    @Override
    /*
    Use Map<String, Object> to accept generic JSON.
    Allow the function to work with the "simplified" JSON sent by LambdaOrchestrator,
    avoiding the "Json Parsing Error" caused by missing fields in the strict S3Event class.
    */
    public String handleRequest(Map<String, Object> event, Context context) {
        LambdaLogger logger = context.getLogger();

        if (event.containsKey("body") && "warmup".equals(event.get("body"))) {
            logger.log("Ping received. Warming up LambdaResizer...");
            return "Warmed up!";
        }


        try {
            logger.log("Started!...");

            // Extracting from the JSON payload sent by Orchestrator
            String srcBucket = (String) event.get("bucket");
            String rawKey = (String) event.get("key");

            if (srcBucket == null || rawKey == null) {
                 logger.log("Error: Missing 'bucket' or 'key' in payload");
                 return "Error: Invalid Input - bucket or key missing";
            }
            
            // S3 keys might be URL-encoded, so decode it.
            String srcKey = URLDecoder.decode(rawKey, StandardCharsets.UTF_8.name());


            
            String dstBucket = "ann-resize-bucket";
            String dstKey = "resized-" + srcKey;

            // // Infer & Validate the image type.
            Matcher matcher = Pattern.compile(REGEX).matcher(srcKey);
            if (!matcher.matches()) {
                logger.log("Unable to infer image type for key: " + srcKey);
                return "";
            }
            // Extract the file extension
            String imageType = matcher.group(1).toLowerCase();
            if (!(JPG_TYPE.equals(imageType)) && !(PNG_TYPE.equals(imageType))) {
                logger.log("Skipping non-image file: " + srcKey);
                return "";
            }


            // Initialize S3 Client
            S3Client s3Client = S3Client.builder().build();
            // Dowload the original image from S3
            // InputStream allows us to read the raw data bytes flowing from S3 into Lambda's memory.
            InputStream s3Object = getObject(s3Client, srcBucket, srcKey);

            // Decode InputStream into a manipulate-able Image object 
            // "srcImage" allows us to access pixel data (width, height, colors)
            BufferedImage srcImage = ImageIO.read(s3Object);
            BufferedImage newImage = resizeImage(srcImage);


            // Create a buffer (a container) to hold the binary data of the new image
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

            // Encode the BufferedImage back into file bytes (JPG or PNG format)
            // This compresses the pixel data and writes it into the "outputStream" container.
            ImageIO.write(newImage, imageType, outputStream);

            // Upload new image to S3
            try {
                putObject(s3Client, outputStream, dstBucket, dstKey, imageType, logger);
                logger.log("Object successfully resized and uploaded to: " + dstBucket);
                return "Object successfully resized";             
            } catch (S3Exception e) { 
                logger.log("AWS S3 Error: " + e.awsErrorDetails().errorMessage());
                return e.awsErrorDetails().errorMessage();
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    

    private InputStream getObject(S3Client s3Client, String bucket, String key) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        return s3Client.getObject(getObjectRequest);
    }


    // Upload the resized image to S3
    private void putObject(S3Client s3Client, ByteArrayOutputStream outputStream,
            String bucket, String key, String imageType, LambdaLogger logger) {
        // Create a "Label" to hold file information (Metadata)
        // Map stores data in Key-Value pairs (e.g., "Size" -> "10MB")        
        Map<String, String> metadata = new HashMap<>();
          
        // Send file size info to S3 (convert to String because Map only accepts String)
        metadata.put("Content-Length", Integer.toString(outputStream.size()));
        
        // This tells the browser "Display this as an image", don't just download it.
        if (JPG_TYPE.equals(imageType)) {
            metadata.put("Content-Type", JPG_MIME);
        } else if (PNG_TYPE.equals(imageType)) {
            metadata.put("Content-Type", PNG_MIME);
        }

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .metadata(metadata)
                .build();

        logger.log("Writing to: " + bucket + "/" + key);
        s3Client.putObject(putObjectRequest,
                RequestBody.fromBytes(outputStream.toByteArray()));
    }




    private BufferedImage resizeImage(BufferedImage srcImage) {
        int srcHeight = srcImage.getHeight();
        int srcWidth = srcImage.getWidth();

        // Math.min - return the smaller scaling factor, to guanrantee the resized image fits within 100x100
        /* Ex : 500*200 image - Get scaling factor = 0.2 -> resized image = 100*40 */
        float scalingFactor = Math.min(
                MAX_DIMENSION / srcWidth, MAX_DIMENSION / srcHeight);
        int width = (int) (scalingFactor * srcWidth);
        int height = (int) (scalingFactor * srcHeight);

        // Create a new blank image (BufferedImage) with the calculated dimensions
        // TYPE_INT_RGB - 3 bytes per pixel (Red, Green, Blue), no transparency
        BufferedImage resizedImage = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_RGB);
        // Create the "Artist" that knows how to draw on this blank image   
        Graphics2D graphics = resizedImage.createGraphics();

        // Handle background transparency
        // Issue - If we draw a transparent PNG onto a blank image, the transparent areas become black.
        // Fix: We paint the entire images WHITE first to act as a background.
        graphics.setPaint(Color.white);
        graphics.fillRect(0, 0, width, height);

        // Set rendering hints for better image quality during scaling
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(srcImage, 0, 0, width, height, null);
    
         // Clean-up, release system resources used by the Graphics2D object
        graphics.dispose();
        return resizedImage;
    }
}