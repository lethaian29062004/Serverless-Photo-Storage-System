# Serverless Photo Storage System

## Description
A cloud-native application designed for storing and managing images using a serverless architecture. The system leverages AWS services to ensure scalability, cost-efficiency, and high availability without the need for managing physical servers. This project was developed as part of a Cloud Computing course at the Vietnamese-German University (VGU).

## Key Features
* **Authentication:** Secure access requiring email and token-based login using HMAC-SHA256.
* **Image Management:** Seamlessly upload, view, and delete images.
* **Image Preview:** Instant preview and thumbnail generation (auto-resizing) for optimized browsing.
* **Privacy Control:** User-controlled privacy settings (Public/Private modes) for each uploaded photo.
* **Orchestration:** A central Lambda function coordinates tasks between S3 storage and the RDS MySQL database.

## Technology Used
**Frontend:** HTML5, CSS3, JavaScript (Fetch API).

**Backend:** Java 17, AWS SDK for Java v2.

**Infrastructure:** AWS Lambda (Serverless), Amazon S3 (Object Storage), Amazon RDS (MySQL), AWS SSM (Parameter Store), AWS Amplify.

## Project Structure
```text
./Serverless-Photo-Storage-System/
│
├── LambdaGenerateToken/         # Generates secure HMAC tokens for user login
├── LambdaGetPhotosDB/           # Retrieves photo lists based on user identity and privacy
├── LambdaInsertDataToDB/        # Manages MySQL RDS records (Metadata)
├── LambdaOrchestrator/          # Main entry point; coordinates S3, DB, and Resizer tasks
├── LambdaResizer/               # Auto-generates thumbnails upon upload
├── LambdaSecureDeleteObject/    # Validates ownership before deleting from S3 and DB
├── LambdaUploadObject/          # Handles direct binary upload to S3
├── index.html                   # Frontend interface (HTML/JavaScript/CSS)
├── .gitignore                   # Standard Git ignore rules for Java/Maven
└── README.md                    # Project documentation


## How To Use The Application

### Access Online
The application is deployed and live at:  
[https://staging.d37iqmh0jvjb1v.amplifyapp.com/](https://staging.d37iqmh0jvjb1v.amplifyapp.com/)

### Run Locally
1. **Clone the repository** to your local machine.
2. Locate the `index.html` file in the root directory.
3. **Open `index.html`** with your desired browser (Chrome, Firefox, or Edge).
*Note: An active internet connection is required to communicate with the AWS Lambda backend endpoints.*

## How to Build and Deploy

### 1. Prerequisites
* **Java 17+** and **Apache Maven** installed.
* An **AWS Account** with S3 buckets, RDS (MySQL), and Lambda configured.
* Environment variables or AWS SSM Parameters set up for database credentials and security keys.

### 2. Build Backend (Lambda Functions)
Navigate into each specific Lambda module folder and run the Maven package command to generate the deployable `.jar` file:
```bash
cd LambdaFunctions/LambdaOrchestrator
mvn clean package
