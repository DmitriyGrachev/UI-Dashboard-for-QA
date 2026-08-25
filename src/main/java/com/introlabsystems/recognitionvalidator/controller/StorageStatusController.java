package com.introlabsystems.recognitionvalidator.controller;

import com.introlabsystems.recognitionvalidator.model.value.StorageStatus;
import com.introlabsystems.recognitionvalidator.service.StorageStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/api/storage")
@RequiredArgsConstructor
public class StorageStatusController {

    private final StorageStatusService storage;

    @GetMapping("/status")
    StorageStatus status() {
        return storage.status();
    }
}
