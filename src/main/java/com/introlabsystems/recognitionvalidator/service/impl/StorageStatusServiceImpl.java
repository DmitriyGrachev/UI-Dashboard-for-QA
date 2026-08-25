package com.introlabsystems.recognitionvalidator.service.impl;

import com.introlabsystems.recognitionvalidator.config.B2StorageProperties;
import com.introlabsystems.recognitionvalidator.dao.jdbc.StorageStatusRepository;
import com.introlabsystems.recognitionvalidator.model.value.StorageStatus;
import com.introlabsystems.recognitionvalidator.service.StorageStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
@RequiredArgsConstructor
public class StorageStatusServiceImpl implements StorageStatusService {

    private final StorageStatusRepository repository;
    private final B2StorageProperties b2Properties;
    private final Clock clock;

    @Override
    public StorageStatus status() {
        return repository.find(
                b2Properties.enabled(),
                clock.instant(),
                b2Properties.metadataRetention()
        );
    }
}
