package com.depositcock.export;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import com.depositcock.deposit.DepositDtos.AnnouncementResponse;
import com.depositcock.security.CurrentSeller;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
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
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
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
