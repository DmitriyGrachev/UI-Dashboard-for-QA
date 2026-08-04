package com.introlabsystems.recognitionvalidator.service;

import java.util.UUID;

public interface AdminUserService {

    void createOperator(String username, String password);

    void deactivateOperator(UUID operatorId);

    void restoreOperator(UUID operatorId);

    void changePassword(UUID operatorId, String password);
}
