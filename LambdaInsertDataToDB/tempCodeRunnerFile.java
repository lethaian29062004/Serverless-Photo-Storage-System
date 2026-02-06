package vgu.cloud26;

// ... (Các imports giữ nguyên) ...

public class LambdaInsertDataToDB implements RequestHandler<Map<String, Object>, String> {

    // --- CẤU HÌNH DATABASE ---
    // Endpoint: Địa chỉ nhà của Database trên AWS
    private static final String RDS_HOST = "database-1.cc38mew6e9au.us-east-1.rds.amazonaws.com";
    private static final int RDS_PORT = 3306; // Cổng mặc định của MySQL
    private static final String DB_USER = "cloud26"; // Tên đăng nhập
    
    // Connection String: Chuỗi địa chỉ đầy đủ để Java tìm thấy Database
    // Cấu trúc: jdbc:mysql://[HOST]:[PORT]/[DB_NAME]
    private static final String JDBC_URL = "jdbc:mysql://" + RDS_HOST + ":" + RDS_PORT + "/Cloud26";

    @Override
    public String handleRequest(Map<String, Object> input, Context context) {
        LambdaLogger logger = context.getLogger();

        try {
            // --- BƯỚC 1: LẤY DỮ LIỆU ĐẦU VÀO ---
            // Orchestrator gửi sang Map gồm: "key" và "description"
            String description = (String) input.get("description");
            String key = (String) input.get("key");

            // Kiểm tra an toàn (Validation)
            if (key == null || key.isEmpty()) {
                throw new RuntimeException("Missing 'key' in payload");
            }
            if (description == null) description = "";

            // --- BƯỚC 2: CHUẨN BỊ DRIVER ---
            // Đây là dòng code "cổ điển" của Java JDBC.
            // Nó giúp Lambda nạp cái thư viện "com.mysql.cj.jdbc.Driver" vào bộ nhớ trước khi dùng.
            Class.forName("com.mysql.cj.jdbc.Driver");

            // --- BƯỚC 3: KẾT NỐI DATABASE (QUAN TRỌNG) ---
            // Lấy các thông tin đăng nhập (gồm cả cái Token bảo mật thay cho mật khẩu)
            Properties props = setMySqlConnectionProperties();
            
            // Cấu trúc "try-with-resources":
            // Java sẽ tự động đóng kết nối (conn.close()) khi chạy xong, dù có lỗi hay không.
            try (Connection conn = DriverManager.getConnection(JDBC_URL, props);
                 // PreparedStatement: Câu lệnh SQL có chỗ trống (?) để điền dữ liệu sau.
                 // Giúp chống hack SQL Injection.
                 PreparedStatement ps = conn.prepareStatement("INSERT INTO Photos (Description, S3Key) VALUES (?, ?)")) {

                // Điền dữ liệu vào 2 dấu hỏi (?)
                ps.setString(1, description); // Dấu ? thứ nhất
                ps.setString(2, key);         // Dấu ? thứ hai
                
                // Thực thi lệnh INSERT
                int rows = ps.executeUpdate();
                
                logger.log("Inserted into DB successfully. Rows affected: " + rows);
                
                // Trả về thông báo thành công cho Orchestrator
                return "{\"success\": true, \"rows_inserted\": " + rows + "}";
            }

        } catch (Exception ex) {
            logger.log("DB Insert Error: " + ex.toString());
            // Ném lỗi ra ngoài để Orchestrator biết Activity 1 bị Fail
            throw new RuntimeException("DB Insert Failed: " + ex.getMessage());
        }
    }

    // --- CÁC HÀM HỖ TRỢ BẢO MẬT (IAM AUTH) ---

    // Hàm này đóng gói User và Token vào một cái túi Properties
    private static Properties setMySqlConnectionProperties() throws Exception {
        Properties mysqlProps = new Properties();
        mysqlProps.setProperty("useSSL", "true"); // Bắt buộc dùng SSL để bảo mật đường truyền
        mysqlProps.setProperty("user", DB_USER);
        
        // QUAN TRỌNG: Thay vì password cố định, ta gọi hàm sinh Token động
        mysqlProps.setProperty("password", generateAuthToken()); 
        return mysqlProps;
    }

    // Hàm sinh "Vé vào cửa" (Token) có hạn dùng 15 phút
    private static String generateAuthToken() throws Exception {
        RdsUtilities rdsUtilities = RdsUtilities.builder().build();
        return rdsUtilities.generateAuthenticationToken(GenerateAuthenticationTokenRequest.builder()
                .hostname(RDS_HOST)
                .port(RDS_PORT)
                .username(DB_USER)
                .region(Region.US_EAST_1)
                .credentialsProvider(DefaultCredentialsProvider.create()) // Lấy quyền từ chính Lambda Role
                .build());
    }
}