package vgu.cloud26;

import java.util.Base64;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/*
Application Basic Workflow
Frontend encode the image file to Base64 string -> send to Orchestrator Lambda
Orchestrator Lambda -> send to Upload Lambda -> decode Base64 -> upload to S3
*/


/* 
Input - <Map<String, Object>>
AWS automatically converts JSON payload received from Orchestrator to a Map (key-value pairs)
String - are default labels : key, content
Object - are the values coresponding to each label :
        Example
    key : goat.png
    content : a base64encodedstring

Output - <String> 
Return a simple JSON string indicating the result of success/failure
*/
public class LambdaUploadObject implements RequestHandler<Map<String, Object>, String> {

    // 
    private static final String BUCKET_NAME = "ann-webapp-bucket";
    private static final Region AWS_REGION = Region.US_EAST_1;
    private static final S3Client s3Client = S3Client.builder()
            .region(AWS_REGION)
            .build();


    @Override
    public String handleRequest(Map<String, Object> input, Context context) {
        LambdaLogger logger = context.getLogger();

        if (input.containsKey("body") && "warmup".equals(input.get("body"))) {
            logger.log("Ping received. Warming up ...");
            return "Warmed up!";
        }


        try {
            
            String key = (String) input.get("key");
            String contentBase64 = (String) input.get("content");

            if (key == null || contentBase64 == null) {
                throw new RuntimeException("Missing 'key' or 'content' in payload.");
            }
           
            // Printed in CloudWatch Logs 
            logger.log("Worker received upload request for key: " + key);

            // To send from Web -> Orchestrator -> Upload Lambda, the file must be encoded to Base64 
            // S3 just receives the data in bytes -> need to decode the file back to bytes       
            byte[] objBytes = Base64.getDecoder().decode(contentBase64);

            
            // PutObjectRequest - is a built-in class from AWS SDK to handle S3 upload requests
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(key)
                    .build();

                
            // RequestBody - built-in class from AWS SDK to handle the body of upload requests
            // putObject() method not receive byte[] directly -> need to wrap it in RequestBody      
            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(objBytes));

            logger.log("Successfully uploaded to S3: " + BUCKET_NAME + "/" + key);


            // 4. Return success message to Orchestrator
            return "{\"success\": true, \"message\": \"Object " + key + " uploaded successfully.\"}";
        } catch (Exception e) {
            logger.log("Upload Worker Error: " + e.getMessage());
            // Throw exception so Orchestrator knows it failed (returns 500/400 status)
            throw new RuntimeException("Upload failed: " + e.getMessage());
        }
    }
}