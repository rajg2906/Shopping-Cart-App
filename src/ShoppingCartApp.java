import java.math.BigDecimal;
import java.sql.*;
import java.util.Scanner;

public class ShoppingCartApp {

    private static final String URL  = "jdbc:mysql://localhost:3306/shopping_cart_db";
    private static final String USER = "user";
    private static final String PASS = "password";

    private static Connection dbConnection;
    private static final Scanner inputScanner = new Scanner(System.in);

    public static void main(String[] args) throws Exception {
        dbConnection = DriverManager.getConnection(URL, USER, PASS);
        dbConnection.setAutoCommit(false);
        System.out.println("+----------------------------------+");
        System.out.println("|     SHOPPING CART APPLICATION    |");
        System.out.println("+----------------------------------+");

        boolean isAppActive = true;
        while (isAppActive) {
            System.out.println("\n+-- MAIN MENU ---------------------+");
            System.out.println("| 1. Add item to cart              |");
            System.out.println("| 2. View cart                     |");
            System.out.println("| 3. Checkout                      |");
            System.out.println("| 4. View products                 |");
            System.out.println("| 0. Exit                          |");
            System.out.println("+----------------------------------+");
            System.out.print("Choice: ");
            String userMenuOption = inputScanner.nextLine().trim();

            switch (userMenuOption) {
                case "1" -> processItemAddition();
                case "2" -> displayBasket();
                case "3" -> processPurchase();
                case "4" -> displayCatalog();
                case "0" -> isAppActive = false;
                default  -> System.out.println("[!] Invalid option.");
            }
        }
        dbConnection.close();
        System.out.println("Goodbye!");
    }

    private static void processItemAddition() throws SQLException {
        int customerIdentifier = askForCustomerIdentifier();
        if (customerIdentifier < 0) return;

        displayCatalog();
        System.out.print("Enter product ID to add: ");
        int itemIdentifier = parseStringToIntOrNegative(inputScanner.nextLine());
        if (itemIdentifier < 0) { System.out.println("[!] Invalid product ID."); return; }

        System.out.print("Enter quantity: ");
        int requestedAmount = parseStringToIntOrNegative(inputScanner.nextLine());
        if (requestedAmount <= 0) { System.out.println("[!] Quantity must be > 0."); return; }

        try {
            String fetchProdSql = "SELECT name, price, stock_qty FROM product WHERE product_id = ?";
            BigDecimal itemCost;
            int availableInventory;
            String itemName;
            try (PreparedStatement prepStmt1 = dbConnection.prepareStatement(fetchProdSql)) {
                prepStmt1.setInt(1, itemIdentifier);
                ResultSet resSet1 = prepStmt1.executeQuery();
                if (!resSet1.next()) {
                    System.out.println("[!] Product not found.");
                    return;
                }
                itemName  = resSet1.getString("name");
                itemCost        = resSet1.getBigDecimal("price");
                availableInventory = resSet1.getInt("stock_qty");
            }

            int existingAmountInBasket = 0;
            int basketIdentifier = retrieveActiveBasketIdentifier(customerIdentifier);
            if (basketIdentifier > 0) {
                String checkExistSql = "SELECT quantity FROM cart_item WHERE cart_id=? AND product_id=?";
                try (PreparedStatement prepStmt2 = dbConnection.prepareStatement(checkExistSql)) {
                    prepStmt2.setInt(1, basketIdentifier); prepStmt2.setInt(2, itemIdentifier);
                    ResultSet resSet2 = prepStmt2.executeQuery();
                    if (resSet2.next()) existingAmountInBasket = resSet2.getInt("quantity");
                }
            }
            int requiredTotalAmount = existingAmountInBasket + requestedAmount;
            if (availableInventory < requestedAmount) {
                System.out.printf("[!] Insufficient stock. Available: %d, Requested: %d%n",
                                  availableInventory, requestedAmount);
                return;
            }

            if (basketIdentifier < 0) basketIdentifier = generateNewBasket(customerIdentifier);

            if (existingAmountInBasket > 0) {
                String updateItemSql = "UPDATE cart_item SET quantity = quantity + ? " +
                                    "WHERE cart_id = ? AND product_id = ?";
                try (PreparedStatement prepStmt3 = dbConnection.prepareStatement(updateItemSql)) {
                    prepStmt3.setInt(1, requestedAmount); prepStmt3.setInt(2, basketIdentifier); prepStmt3.setInt(3, itemIdentifier);
                    prepStmt3.executeUpdate();
                }
            } else {
                String insertItemSql = "INSERT INTO cart_item (cart_id, product_id, quantity) VALUES (?,?,?)";
                try (PreparedStatement prepStmt4 = dbConnection.prepareStatement(insertItemSql)) {
                    prepStmt4.setInt(1, basketIdentifier); prepStmt4.setInt(2, itemIdentifier); prepStmt4.setInt(3, requestedAmount);
                    prepStmt4.executeUpdate();
                }
            }

            String updateStockSql = "UPDATE product SET stock_qty = stock_qty - ? WHERE product_id = ?";
            try (PreparedStatement prepStmt5 = dbConnection.prepareStatement(updateStockSql)) {
                prepStmt5.setInt(1, requestedAmount); prepStmt5.setInt(2, itemIdentifier);
                prepStmt5.executeUpdate();
            }

            dbConnection.commit();
            System.out.printf("[+] Added %dx '%s' to cart #%d.%n", requestedAmount, itemName, basketIdentifier);

        } catch (SQLException e) {
            dbConnection.rollback();
            System.out.println("[!] Transaction rolled back: " + e.getMessage());
        }
    }

    private static void displayBasket() throws SQLException {
        int customerIdentifier = askForCustomerIdentifier();
        if (customerIdentifier < 0) return;
        int basketIdentifier = retrieveActiveBasketIdentifier(customerIdentifier);
        if (basketIdentifier < 0) { System.out.println("[i] No active cart found."); return; }
        renderBasket(basketIdentifier);
    }

    private static void processPurchase() throws SQLException {
        int customerIdentifier = askForCustomerIdentifier();
        if (customerIdentifier < 0) return;

        int basketIdentifier = retrieveActiveBasketIdentifier(customerIdentifier);
        if (basketIdentifier < 0) { System.out.println("[!] No active cart to checkout."); return; }

        renderBasket(basketIdentifier);

        String sumSql = "SELECT SUM(ci.quantity * p.price) AS total " +
                          "FROM cart_item ci JOIN product p ON ci.product_id = p.product_id " +
                          "WHERE ci.cart_id = ?";
        BigDecimal requiredAmount;
        try (PreparedStatement prepStmt6 = dbConnection.prepareStatement(sumSql)) {
            prepStmt6.setInt(1, basketIdentifier);
            ResultSet resSet3 = prepStmt6.executeQuery();
            resSet3.next();
            requiredAmount = resSet3.getBigDecimal("total");
            if (requiredAmount == null || requiredAmount.compareTo(BigDecimal.ZERO) == 0) {
                System.out.println("[!] Cart is empty. Add items first.");
                return;
            }
        }

        System.out.printf("%nOrder total : %.2f%n", requiredAmount);

        BigDecimal availableFunds = retrieveAvailableFunds(customerIdentifier);
        System.out.printf("Wallet balance : %.2f%n", availableFunds);
        
        if (availableFunds.compareTo(requiredAmount) < 0) {
            System.out.printf("[!] Insufficient wallet balance. Need %.2f, have %.2f%n",
                              requiredAmount, availableFunds);
            System.out.println("[-] Emptying cart and returning items to inventory...");
            
            try {
                String replenishInventorySql = "UPDATE product p JOIN cart_item ci ON p.product_id = ci.product_id " +
                                               "SET p.stock_qty = p.stock_qty + ci.quantity " +
                                               "WHERE ci.cart_id = ?";
                try (PreparedStatement prepStmtReplenish = dbConnection.prepareStatement(replenishInventorySql)) {
                    prepStmtReplenish.setInt(1, basketIdentifier);
                    prepStmtReplenish.executeUpdate();
                }

                String emptyCartSql = "DELETE FROM cart_item WHERE cart_id = ?";
                try (PreparedStatement prepStmtEmpty = dbConnection.prepareStatement(emptyCartSql)) {
                    prepStmtEmpty.setInt(1, basketIdentifier);
                    prepStmtEmpty.executeUpdate();
                }

                dbConnection.commit();
                System.out.println("[+] Cart emptied and inventory replenished successfully.");
            } catch (SQLException e) {
                dbConnection.rollback();
                System.out.println("[!] Failed to replenish inventory. Transaction rolled back: " + e.getMessage());
            }
            return;
        }

        System.out.print("Confirm checkout? (y/n): ");
        if (!inputScanner.nextLine().trim().equalsIgnoreCase("y")) {
            System.out.println("Checkout cancelled.");
            return;
        }

        try {
            String deductFundsSql = "UPDATE wallet SET balance = balance - ? WHERE user_id = ?";
            try (PreparedStatement prepStmt7 = dbConnection.prepareStatement(deductFundsSql)) {
                prepStmt7.setBigDecimal(1, requiredAmount); prepStmt7.setInt(2, customerIdentifier);
                prepStmt7.executeUpdate();
            }

            String markBasketDoneSql = "UPDATE cart SET status = 'CHECKED_OUT' WHERE cart_id = ?";
            try (PreparedStatement prepStmt8 = dbConnection.prepareStatement(markBasketDoneSql)) {
                prepStmt8.setInt(1, basketIdentifier);
                prepStmt8.executeUpdate();
            }

            int invoiceIdentifier;
            String createInvoiceSql = "INSERT INTO bill (cart_id, user_id, total_amount) VALUES (?,?,?)";
            try (PreparedStatement prepStmt9 = dbConnection.prepareStatement(createInvoiceSql, Statement.RETURN_GENERATED_KEYS)) {
                prepStmt9.setInt(1, basketIdentifier); prepStmt9.setInt(2, customerIdentifier); prepStmt9.setBigDecimal(3, requiredAmount);
                prepStmt9.executeUpdate();
                ResultSet genKeys1 = prepStmt9.getGeneratedKeys();
                genKeys1.next();
                invoiceIdentifier = genKeys1.getInt(1);
            }

            String snapshotItemsSql = "SELECT ci.product_id, p.name, p.price, ci.quantity " +
                             "FROM cart_item ci JOIN product p ON ci.product_id = p.product_id " +
                             "WHERE ci.cart_id = ?";
            String saveSnapshotSql = "INSERT INTO bill_item (bill_id, product_id, product_name, quantity, unit_price) " +
                           "VALUES (?,?,?,?,?)";
            try (PreparedStatement fetchItemsStmt = dbConnection.prepareStatement(snapshotItemsSql);
                 PreparedStatement insertItemsStmt = dbConnection.prepareStatement(saveSnapshotSql)) {
                fetchItemsStmt.setInt(1, basketIdentifier);
                ResultSet resSet4 = fetchItemsStmt.executeQuery();
                while (resSet4.next()) {
                    insertItemsStmt.setInt(1, invoiceIdentifier);
                    insertItemsStmt.setInt(2, resSet4.getInt("product_id"));
                    insertItemsStmt.setString(3, resSet4.getString("name"));
                    insertItemsStmt.setInt(4, resSet4.getInt("quantity"));
                    insertItemsStmt.setBigDecimal(5, resSet4.getBigDecimal("price"));
                    insertItemsStmt.addBatch();
                }
                insertItemsStmt.executeBatch();
            }

            dbConnection.commit();

            renderInvoice(invoiceIdentifier);
            System.out.printf("%n[+] Checkout successful! Remaining balance: %.2f%n",
                              retrieveAvailableFunds(customerIdentifier));

        } catch (SQLException e) {
            dbConnection.rollback();
            System.out.println("[!] Checkout failed, rolled back: " + e.getMessage());
        }
    }

    private static int retrieveActiveBasketIdentifier(int customerIdentifier) throws SQLException {
        String checkActiveBasketSql = "SELECT cart_id FROM cart WHERE user_id = ? AND status = 'ACTIVE' LIMIT 1";
        try (PreparedStatement prepStmt10 = dbConnection.prepareStatement(checkActiveBasketSql)) {
            prepStmt10.setInt(1, customerIdentifier);
            ResultSet resSet5 = prepStmt10.executeQuery();
            return resSet5.next() ? resSet5.getInt("cart_id") : -1;
        }
    }

    private static int generateNewBasket(int customerIdentifier) throws SQLException {
        String newBasketSql = "INSERT INTO cart (user_id) VALUES (?)";
        try (PreparedStatement prepStmt11 = dbConnection.prepareStatement(newBasketSql, Statement.RETURN_GENERATED_KEYS)) {
            prepStmt11.setInt(1, customerIdentifier);
            prepStmt11.executeUpdate();
            ResultSet genKeys2 = prepStmt11.getGeneratedKeys();
            genKeys2.next();
            return genKeys2.getInt(1);
        }
    }

    private static BigDecimal retrieveAvailableFunds(int customerIdentifier) throws SQLException {
        String checkFundsSql = "SELECT balance FROM wallet WHERE user_id = ?";
        try (PreparedStatement prepStmt12 = dbConnection.prepareStatement(checkFundsSql)) {
            prepStmt12.setInt(1, customerIdentifier);
            ResultSet resSet6 = prepStmt12.executeQuery();
            if (resSet6.next()) return resSet6.getBigDecimal("balance");
        }
        return BigDecimal.ZERO;
    }

    private static int askForCustomerIdentifier() throws SQLException {
        System.out.print("Enter user ID: ");
        int customerIdentifier = parseStringToIntOrNegative(inputScanner.nextLine());
        if (customerIdentifier < 0) { System.out.println("[!] Invalid user ID."); return -1; }
        String checkUserSql = "SELECT user_id FROM users WHERE user_id = ?";
        try (PreparedStatement prepStmt13 = dbConnection.prepareStatement(checkUserSql)) {
            prepStmt13.setInt(1, customerIdentifier);
            ResultSet resSet7 = prepStmt13.executeQuery();
            if (!resSet7.next()) { System.out.println("[!] User not found."); return -1; }
        }
        return customerIdentifier;
    }

    private static void displayCatalog() throws SQLException {
        System.out.println("\n+------+--------------------------+-----------+-------+");
        System.out.println("|  ID  | Product                  |   Price   | Stock |");
        System.out.println("+------+--------------------------+-----------+-------+");
        String listProdsSql = "SELECT product_id, name, price, stock_qty FROM product ORDER BY product_id";
        try (Statement bareStmt1 = dbConnection.createStatement();
             ResultSet resSet8 = bareStmt1.executeQuery(listProdsSql)) {
            while (resSet8.next()) {
                System.out.printf("| %4d | %-24s | %9.2f | %5d |%n",
                    resSet8.getInt("product_id"),
                    formatTextLength(resSet8.getString("name"), 24),
                    resSet8.getBigDecimal("price"),
                    resSet8.getInt("stock_qty"));
            }
        }
        System.out.println("+------+--------------------------+-----------+-------+");
    }

    private static void renderBasket(int basketIdentifier) throws SQLException {
        System.out.printf("%n+-- Cart #%-4d ---------------------------------------------------+%n", basketIdentifier);
        System.out.println("|  ID  | Product                  |   Price   | Qty |   Subtotal |");
        System.out.println("+------+--------------------------+-----------+-----+------------+");
        String fetchBasketSql = "SELECT p.product_id, p.name, p.price, ci.quantity, " +
                     "(p.price * ci.quantity) AS subtotal " +
                     "FROM cart_item ci JOIN product p ON ci.product_id = p.product_id " +
                     "WHERE ci.cart_id = ?";
        BigDecimal finalAmount = BigDecimal.ZERO;
        try (PreparedStatement prepStmt14 = dbConnection.prepareStatement(fetchBasketSql)) {
            prepStmt14.setInt(1, basketIdentifier);
            ResultSet resSet9 = prepStmt14.executeQuery();
            boolean isBasketEmpty = true;
            while (resSet9.next()) {
                isBasketEmpty = false;
                BigDecimal rowTotal = resSet9.getBigDecimal("subtotal");
                finalAmount = finalAmount.add(rowTotal);
                System.out.printf("| %4d | %-24s | %9.2f | %3d | %10.2f |%n",
                    resSet9.getInt("product_id"),
                    formatTextLength(resSet9.getString("name"), 24),
                    resSet9.getBigDecimal("price"),
                    resSet9.getInt("quantity"),
                    rowTotal);
            }
            if (isBasketEmpty) System.out.println("|                         (cart is empty)                        |");
        }
        System.out.println("+------+--------------------------+-----------+-----+------------+");
        System.out.printf("| %49s | %10.2f |%n", "TOTAL", finalAmount);
        System.out.println("+---------------------------------------------------+------------+");
    }

    private static void renderInvoice(int invoiceIdentifier) throws SQLException {
        String invoiceHdrSql = "SELECT b.bill_id, b.billed_at, b.total_amount, u.name " +
                         "FROM bill b JOIN users u ON b.user_id = u.user_id " +
                         "WHERE b.bill_id = ?";
        try (PreparedStatement prepStmt15 = dbConnection.prepareStatement(invoiceHdrSql)) {
            prepStmt15.setInt(1, invoiceIdentifier);
            ResultSet resSet10 = prepStmt15.executeQuery();
            if (resSet10.next()) {
                System.out.println("\n+-------------------------------------------------------+");
                System.out.printf( "|  BILL #%-47d|%n", invoiceIdentifier);
                System.out.printf( "|  Customer : %-42s|%n", resSet10.getString("name"));
                System.out.printf( "|  Date     : %-42s|%n", resSet10.getTimestamp("billed_at"));
                System.out.println("+-----------+--------------------------+-----+----------+");
                System.out.println("|  Prod ID  | Product                  | Qty |  Amount  |");
                System.out.println("+-----------+--------------------------+-----+----------+");
            }
        }
        String invoiceItemsSql = "SELECT product_id, product_name, quantity, unit_price, line_total " +
                         "FROM bill_item WHERE bill_id = ?";
        try (PreparedStatement prepStmt16 = dbConnection.prepareStatement(invoiceItemsSql)) {
            prepStmt16.setInt(1, invoiceIdentifier);
            ResultSet resSet11 = prepStmt16.executeQuery();
            while (resSet11.next()) {
                System.out.printf("|  %7d  | %-24s | %3d | %8.2f |%n",
                    resSet11.getInt("product_id"),
                    formatTextLength(resSet11.getString("product_name"), 24),
                    resSet11.getInt("quantity"),
                    resSet11.getBigDecimal("line_total"));
            }
        }
        String invoiceTotSql = "SELECT total_amount FROM bill WHERE bill_id = ?";
        try (PreparedStatement prepStmt17 = dbConnection.prepareStatement(invoiceTotSql)) {
            prepStmt17.setInt(1, invoiceIdentifier);
            ResultSet resSet12 = prepStmt17.executeQuery();
            resSet12.next();
            System.out.println("+-----------+--------------------------+-----+----------+");
            System.out.printf( "| %42s | %8.2f |%n", "TOTAL", resSet12.getBigDecimal("total_amount"));
            System.out.println("+--------------------------------------------+----------+");
        }
    }

    private static int parseStringToIntOrNegative(String rawString) {
        try { return Integer.parseInt(rawString.trim()); }
        catch (NumberFormatException exc) { return -1; }
    }

    private static String formatTextLength(String rawString, int maxLen) {
        return rawString.length() <= maxLen ? rawString : rawString.substring(0, maxLen - 1) + "…";
    }
}
