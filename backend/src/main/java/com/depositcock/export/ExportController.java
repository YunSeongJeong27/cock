package com.depositcock.export;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import com.depositcock.deposit.DepositDtos.AnnouncementResponse;
import com.depositcock.security.CurrentSeller;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ExportController {

    private final ExportService service;

    public ExportController(ExportService service) {
        this.service = service;
    }

    @GetMapping("/api/seller/group-buys/{groupBuyId}/export.xlsx")
    public ResponseEntity<byte[]> orderSheet(@PathVariable Long groupBuyId,
                                             @RequestParam(defaultValue = "false") boolean includeContact) {
        ExportService.ExcelFile file = service.orderSheet(CurrentSeller.id(), groupBuyId, includeContact);
        return ResponseEntity.ok()
                // filename 은 영문 대체 이름, filename* 은 한글 이름 (RFC 6266, 대부분 브라우저는 filename* 을 쓴다)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"order-sheet.xlsx\"; filename*=UTF-8''"
                        + URLEncoder.encode(file.fileName(), StandardCharsets.UTF_8).replace("+", "%20"))
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(file.content());
    }

    @GetMapping("/api/seller/group-buys/{groupBuyId}/announcement")
    public AnnouncementResponse announcement(@PathVariable Long groupBuyId,
                                             @RequestParam(required = false)
                                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since) {
        return service.announcement(CurrentSeller.id(), groupBuyId, since);
    }
}
