package dustin.cex.domains.auth.model.entity;

/** 고객 업무와 운영 요청·승인·감사 권한을 서로 분리한다. */
public enum UserRole { CUSTOMER, OPERATOR, APPROVER, AUDITOR }
