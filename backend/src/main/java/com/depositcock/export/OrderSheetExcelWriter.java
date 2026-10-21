package com.depositcock.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.depositcock.groupbuy.GroupBuy;
import com.depositcock.groupbuy.Product;
import com.depositcock.order.CustomerOrder;
import com.depositcock.order.OrderItem;
import com.depositcock.order.PaymentMethod;
import com.depositcock.order.PhoneNumbers;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * 주문 정리표 xlsx (정리본 9장). 판매자가 내려받아 쓰는 파일이다 (은행 거래내역과 별개).
 * 열: 주문번호, 닉네임, 상품별 수량, 금액, 결제 방식, 입금 여부, 입금자명, 수령, 확인필요 (+ 선택: 연락처·배송지 열)
 * - 시트 이름은 WorkbookUtil.createSafeSheetName
 * - 열 너비는 칸 내용 길이에 맞춘다 (fitColumns)
 */
@Component
public class OrderSheetExcelWriter {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 열 너비 상한 (글자 수). 긴 주소도 이 폭에서 잘려 보이고 칸을 누르면 다 보인다 */
    private static final int MAX_COLUMN_WIDTH = 60;

    public byte[] write(GroupBuy groupBuy, List<CustomerOrder> orders, boolean includeContact) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle headerStyle = workbook.createCellStyle();
            Font bold = workbook.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle moneyStyle = workbook.createCellStyle();
            moneyStyle.setDataFormat(workbook.createDataFormat().getFormat("#,##0"));

            List<Product> products = groupBuy.sortedProducts();
            String mainName = WorkbookUtil.createSafeSheetName(safeTitle(groupBuy.getTitle()));
            writeSheet(workbook.createSheet(mainName),
                    products, orders.stream().filter(CustomerOrder::isActive).toList(), includeContact,
                    headerStyle, moneyStyle);
            List<CustomerOrder> cancelled = orders.stream().filter(o -> !o.isActive()).toList();
            if (!cancelled.isEmpty()) {
                // 시트 이름은 대소문자 무시하고 겹치면 안 된다 (공구 제목이 "취소된 주문"이면 500 이 나던 문제, 2026-10-19)
                String cancelledName = mainName.equalsIgnoreCase("취소된 주문") ? "취소된 주문 (2)" : "취소된 주문";
                writeSheet(workbook.createSheet(cancelledName), products, cancelled,
                        includeContact, headerStyle, moneyStyle);
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("엑셀 파일을 만들 수 없습니다.", e);
        }
    }

    private void writeSheet(Sheet sheet, List<Product> products, List<CustomerOrder> orders, boolean includeContact,
                            CellStyle headerStyle, CellStyle moneyStyle) {
        List<String> headers = new ArrayList<>(List.of("주문번호", "닉네임"));
        products.forEach(p -> headers.add(p.getName()));
        // 수령 방법·픽업 지점은 연락처가 아니므로 기본 정리표에도 넣는다 (픽업 지점별로 나눠 챙길 때 필요)
        headers.addAll(List.of("금액", "결제 방식", "입금 여부", "입금자명", "수령 방법", "픽업 지점", "수령", "확인필요", "주문 상태"));
        if (includeContact) {
            headers.addAll(List.of("휴대폰", "동·호수", "받는 분", "받는 분 연락처", "우편번호", "주소", "상세주소"));
        }
        headers.add("주문 시각");

        Row header = sheet.createRow(0);
        for (int i = 0; i < headers.size(); i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(headers.get(i));
            cell.setCellStyle(headerStyle);
        }
        sheet.createFreezePane(0, 1);

        int r = 1;
        int[] productTotals = new int[products.size()];
        long amountTotal = 0;
        for (CustomerOrder o : orders) {
            Row row = sheet.createRow(r++);
            int c = 0;
            row.createCell(c++).setCellValue(o.getOrderNo());
            row.createCell(c++).setCellValue(nullToEmpty(o.getNickname()));
            for (int p = 0; p < products.size(); p++) {
                int qty = quantity(o, products.get(p));
                productTotals[p] += qty;
                Cell cell = row.createCell(c++);
                if (qty > 0) {
                    cell.setCellValue(qty);
                }
            }
            Cell amount = row.createCell(c++);
            amount.setCellValue(o.getExpectedAmount());
            amount.setCellStyle(moneyStyle);
            amountTotal += o.getExpectedAmount();
            row.createCell(c++).setCellValue(o.getPaymentMethod().getLabel());
            row.createCell(c++).setCellValue(paidText(o));
            row.createCell(c++).setCellValue(nullToEmpty(o.getDepositorName()));
            row.createCell(c++).setCellValue(o.getReceiveMethod().getLabel());
            row.createCell(c++).setCellValue(o.getPickupPoint() == null ? "" : o.getPickupPoint().getName());
            row.createCell(c++).setCellValue(o.isPickedUp() ? "수령" : "");
            row.createCell(c++).setCellValue(String.join(", ", checks(o)));
            row.createCell(c++).setCellValue(o.displayStatus().getLabel());
            if (includeContact) {
                row.createCell(c++).setCellValue(nullToEmpty(PhoneNumbers.format(o.getPhone())));
                row.createCell(c++).setCellValue(nullToEmpty(o.getUnitAddress()));
                row.createCell(c++).setCellValue(nullToEmpty(o.getRecipientName()));
                row.createCell(c++).setCellValue(nullToEmpty(PhoneNumbers.format(o.getRecipientPhone())));
                row.createCell(c++).setCellValue(nullToEmpty(o.getZipcode()));
                row.createCell(c++).setCellValue(nullToEmpty(o.getAddress()));
                row.createCell(c++).setCellValue(nullToEmpty(o.getAddressDetail()));
            }
            row.createCell(c).setCellValue(o.getOrderedAt().format(DATE_TIME));
        }

        Row total = sheet.createRow(r);
        total.createCell(0).setCellValue("합계");
        total.createCell(1).setCellValue(orders.size() + "건");
        for (int p = 0; p < products.size(); p++) {
            total.createCell(2 + p).setCellValue(productTotals[p]);
        }
        Cell amountCell = total.createCell(2 + products.size());
        amountCell.setCellValue(amountTotal);
        amountCell.setCellStyle(moneyStyle);
        for (Cell cell : total) {
            cell.setCellStyle(cell.getColumnIndex() == 2 + products.size() ? moneyStyle : headerStyle);
        }
        fitColumns(sheet, headers.size());
    }

    /**
     * 열 너비를 칸에 보이는 글자(금액은 "3,800"처럼 서식 적용 뒤)의 가장 긴 길이에 맞춘다.
     * 한글·한자는 영문·숫자의 2칸으로 센다. 글자 수로 재므로 서버에 깔린 폰트와 상관없이 같은 너비가 나온다
     */
    private static void fitColumns(Sheet sheet, int columns) {
        DataFormatter formatter = new DataFormatter();
        int[] widths = new int[columns];
        for (Row row : sheet) {
            for (Cell cell : row) {
                int i = cell.getColumnIndex();
                widths[i] = Math.max(widths[i], displayWidth(formatter.formatCellValue(cell)));
            }
        }
        for (int i = 0; i < columns; i++) {
            // 앞뒤 여백 2칸, 아주 짧은 열도 최소 6칸
            sheet.setColumnWidth(i, Math.min(MAX_COLUMN_WIDTH, Math.max(6, widths[i] + 2)) * 256);
        }
    }

    static int displayWidth(String text) {
        return text.codePoints()
                .map(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HANGUL || Character.isIdeographic(c) ? 2 : 1)
                .sum();
    }

    private static List<String> checks(CustomerOrder o) {
        List<String> checks = new ArrayList<>();
        if (!o.isActive()) {
            if (o.isRefundRequired() && o.getRefundedAt() == null) {
                checks.add("환불 필요");
            } else if (o.isRefundRequired()) {
                checks.add("환불 완료");
            }
            return checks;
        }
        if (o.isPrepaid() && !o.isPaid() && o.isPickedUp()) {
            checks.add("미입금 수령");
        }
        if (o.isPaid() && o.getPaidAmount() != null && o.getPaidAmount() != o.getExpectedAmount()) {
            checks.add("입금액 차이 " + (o.getPaidAmount() - o.getExpectedAmount()) + "원");
        }
        return checks;
    }

    private static String paidText(CustomerOrder o) {
        if (o.getPaymentMethod() == PaymentMethod.ONSITE) {
            return o.isOnsitePaid() ? "현장결제 완료" : "현장결제 예정";
        }
        return o.isPaid() ? "입금 확인" : "미입금";
    }

    private static int quantity(CustomerOrder o, Product product) {
        return o.getItems().stream().filter(i -> i.getProduct().getId().equals(product.getId()))
                .mapToInt(OrderItem::getQuantity).sum();
    }

    private static String safeTitle(String title) {
        return title == null || title.isBlank() ? "주문 정리표" : title;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
