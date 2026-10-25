# 미검토 상대에게 송수신인 정보를 제공하지 않도록 전송 전에 차단하기

기술 회원이라는 이유만으로 고객 이름을 외부에 제공하지 않도록, 검토한 상대 사업자의 명시적 허용 설정을 실제 정보 교환 클라이언트의 전송 전에 검사한다. 기본은 아무 상대도 허용하지 않는다. 실사/계약 자체와 코드의 허용 검사, 현재 디렉터리/고객 판단을 구분한다.

## 문제와 공식 자료의 접근

[CodeVASP 상호 연동 가이드](https://docs.codevasp.com/en/docs/travel-rule/guides/02-Development/12-Interoperability-with-Other-Protocols)를2026-10-25 다시 확인했다. 목록은 기술 연결 가능 사업자이며 실제 연결은 내부 정책과 실사 판단이 필요하다. 서버 상태 역시 정책적 연동 승인이 아니다. 따라서 Directory의 reachable이나 FOUND만으로 개인정보 전송을 시작할 수 없다.

104의 클라이언트는 주어진 상대의 기술 형식만 검사했다. 이번에는 그 내부 클라이언트를 다른 업무에서 직접 호출해도 미허용 상대에게 이름을 보내지 못하도록 전송 경계에 정책을 연결했다. 실제 현재 디렉터리/검색/고객 확인은 다음 업무의 책임이며 외부 요청자가 상대 객체를 만드는 HTTP API는 없다.

## 선택과 트레이드오프

| 대안/선택 | 이점 | 비용과 제약 |
| --- | --- | --- |
|기술 목록 전체 자동 허용 | 설정 부담이 작음 | 기술 회원을 정책 승인으로 오인하고 미검토 상대에게 개인정보를 제공할 수 있어 제외 |
|별도 온라인 사업자 승인 시스템 | 변경 즉시 반영·업무별 감사 가능 | 현재 범위에 별도 관리자 업무/권한/저장/화면을 추가해야 함. 운영 통제 없는 단순 승인 버튼은 실사를 증명하지 않음 |
|검토된 명시적 운영 허용 설정 채택 | 추가 DB/관리 API 없이 기본 거부·정확한 사업자/기간 검사, 코드/설정 검토 절차 적용 가능 | 변경·철회에 프로세스 재시작 필요. 배포 설정 이중 검토·증적 보관·긴급 차단은 운영 절차이며 이번 코드로 완료한 것이 아님 |
|정책 전체 지문과 실사 증적 지문 | 후속 실행이 어떤 정책 근거였는지 식별 가능 | 공개 해시는 서명/검토자 증명/증적 저장을 대신하지 않음. 원본 실사 문서 별도 보관 필요 |
|법인명 정확 일치·유효한 키의 결정적 선택 | 이름 변경/지난 키를 조용히 받아들이지 않음 | 법인명 변경 시 재검토/설정 갱신 필요. 자동 별칭·유사도 일치는 없음 |

구현 지연 자체를 비용으로 삼지 않는다. 현재 CODE 국내 개인 업무에 필요한 작은 허용 경계를 선택했으며 임시 폴백이나 자동 전체 허용을 두지 않았다. 설정은 실제 확인된 사업자만 운영에서 검토해 넣어야 한다. 샘플 사업자를 제품 기본 허용으로 추가하지 않았다.

## 설정과 구현

`codevasp.approved-counterparties`는 JSON 배열 문자열이며 기본은 `[]`다. 배열 최대1000개/원문64KiB, 중복 필드·사업자/연속 JSON·추가 필드·잘못된 기간/지문은 시작 시 거부한다. 필수 여섯 필드는 entityId, legalName, reviewReference, evidenceSha256, validFrom, validUntil이다. 국가 KR/연동 CODE는 현재 코드 범위로 고정하며 임의 범용 옵션으로 늘리지 않는다.

reviewReference는 승인된 운영 검토 문서 참조이고 evidenceSha256은 그 증적의 공개 지문이다. 실사 결과/계약 조건·개인정보 처리 근거·제재/등록/관계 확인·승인자 분리와 갱신 주기는 운영에서 실제 확인해야 한다. 값을 넣는 행위만으로 실사/법적 신고/이중 승인 완료라고 표시하지 않는다. 고객 이름·주소·개인키나 문서 원문은 설정에 넣지 않는다.

각 제공 시점에 허용 시작은 포함하고 종료는 제외한다. 현재 상대 entity ID/정확한 법인명/KR/CODE/가용 상태와 일치해야 한다. 유효한 키가 여러 개면 만료가 가장 늦은 키, 동률이면 공개키 문자열 순서로 선택한다. 현재 키가 모두 지난 경우는 거부한다. 정책은 entity ID로 정렬해 표준화한 뒤 SHA-256 지문을 계산해 JSON 공백/배열 순서만 다른 경우 같은 지문을 얻는다. 이름/기간/참조/증적 변경은 새 지문이다.

정보 교환 클라이언트에서도 정책과 선택 키가 맞는지 검사하고 불일치면 NOT_SENT로 종료한다. 이 검사는 호출자가 넘긴 사업자 메타데이터의 진위/현재성이나 송신인·현재 KYC 일치를 증명하지 않는다. 반드시 현재 디렉터리를 잠근 후속 업무가 원본을 제공해야 한다. 정책 검사 뒤 키 만료는 기존 전송 경계에서 한 번 더 확인한다. 다른 프로세스에 새 설정이 배포돼도 이전 프로세스의 메모리 설정이 자동 철회되는 구조는 아니므로 전 인스턴스 교체/차단이 운영 전제다.

## 실제 코드와 문법

출처: [CodeVaspCounterpartyPolicy.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspCounterpartyPolicy.java#L49), 현재 소스의 부분 발췌.

```java
        var approval=approvals.get(entityId);
        if(approval==null||now.isBefore(approval.validFrom())||!now.isBefore(approval.validUntil())||directory==null)throw held();
        var peers=directory.vasps().stream().filter(p->entityId.equals(p.entityId())).toList();if(peers.size()!=1)throw held();var peer=peers.getFirst();
        if(!"KR".equals(peer.country())||!"CODE".equalsIgnoreCase(peer.alliance())||!peer.reachable()||!approval.legalName().equals(peer.legalName()))throw held();
        // 동시에 유효한 키가 여러 개면 만료가 늦은 키, 같은 만료면 공개키 문자열 순서로 결정해 실행 재현성을 확보한다.
```

Instant는 시간대 없는 로컬 시각이 아니라 절대 시각이다. isBefore를 조합해 시작 포함/끝 제외를 표현한다. Java Map은 생성 시 복사되어 런타임 외부 입력으로 허용 범위를 바꾸지 않는다. 정확 일치는 임의 문자열 정규화로 다른 법인을 연결하지 않기 위한 제약이다.

출처: [CodeVaspCounterpartyPolicy.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspCounterpartyPolicy.java#L54), 현재 소스의 부분 발췌.

```java
        var key=peer.keys().stream().filter(k->k.expiresAt()!=null&&k.expiresAt().isAfter(now)&&CodeVaspSettings.key(k.publicKey(),32))
            .max(Comparator.comparing(CodeVaspDirectoryClient.Key::expiresAt).thenComparing(CodeVaspDirectoryClient.Key::publicKey)).orElseThrow(CodeVaspCounterpartyPolicy::held);
        return new Selection(policyHash,approval,peer,key);
```

Stream.filter로 현재 키만 남기고 Comparator를 연결해 순서에 의존하지 않는 선택을 한다. TypeScript의 filter/sort와 목적은 같지만 Optional이 비었을 때 orElseThrow로 명시적으로 거부한다.

출처: [CodeVaspTransferDataClient.java](../../src/main/java/dustin/cex/domains/travelrule/CodeVaspTransferDataClient.java#L47), 현재 소스의 부분 발췌.

```java
            var now=Instant.now();var selected=policy.select(new CodeVaspDirectoryClient.Directory(now,now,List.of(request.peer())),request.peer().entityId());
            if(!selected.key().equals(request.key()))throw unavailable();
        }catch(RuntimeException denied){throw new CodeVaspSubmissionFailure(CodeVaspSubmissionFailure.Admission.NOT_SENT);}
```

실제 전송 전에 정책을 검사한다. 거부는 현재 호출의 미전송이며 과거 호출 부재나 금융 효과 미발생의 증명이 아니다. 현재 디렉터리의 원본 검증은 별도 호출 업무가 수행해야 한다.

## 작성한 테스트

[CodeVaspCounterpartyPolicyTest](../../src/test/java/dustin/cex/domains/travelrule/CodeVaspCounterpartyPolicyTest.java)9개를 추가했다.

| 함수 | 시나리오·기대 결과 |
| --- | --- |
|absentApprovalCannotUseTechnicallyReachableMember | 기술 가용 사업자여도 미허용이면503 |
|currentExactBusinessAndKeyProduceBoundSelection | 정확한 현재 법인/키·검토 참조/정책 지문 결합 |
|approvalPeriodHasInclusiveStartAndExclusiveEnd | 시작 포함/시작 전 거부/끝 제외 |
|changedNameCountryAllianceHealthOrDuplicateMemberCannotBorrowApproval | 다른 법인/국가/연동·중단·중복/누락 사업자 거부 |
|expiredOrInvalidKeysCannotBeSelected | 만료 경계/불량 키 거부 |
|keyChoiceIsIndependentOfSourceArrayOrder | 원본 키 순서를 바꿔도 만료가 늦은 키 선택 |
|sameSemanticPolicyKeepsHashAndChangesInvalidateIt | 배열/공백 차이는 같은 지문, 사업자명/검토/증적 변경은 다른 지문 |
|malformedOrAmbiguousPolicyFailsStartupWithoutEcho | 중복/추가/미지 필드·연속 JSON/불량 참조·지문·기간/큰 원문 거부, 원문 원인 비노출 |
|policyDescriptionDoesNotEchoOperationalEvidence | 문자열 출력에 운영 증적 참조 없음 |

[CodeVaspTransferDataClientTest](../../src/test/java/dustin/cex/domains/travelrule/CodeVaspTransferDataClientTest.java)의 기존17개는 명시적인 가상 시험 허용 정책을 전달하도록 갱신했다. technicallyReachableButUnapprovedPeerCannotDiscloseIdentity를 추가해 실제 HTTP 경계에서 기술 가용 사업자도 미허용이면 서명/게이트웨이/복호화 호출0이고 NOT_SENT임을 검증한다. 허용 시험용 키/사업자는 제품 설정에 들어가지 않는다.

## 실행 명령과 결과

Java21/macOS arm64/소유 PG16·HTTP 대역. 먼저 정책9개를 단독 실행해 통과했고, 연결 후 `python3 scripts/verify_codevasp_directory.py` 최종81개(기존71+정책9+미허용 실제 전송 차단1), 실패/오류/제외0. 중간9개를 최종 합계에 다시 더하지 않는다. bootJar/operatorAdminJar 성공, 소유 PG 제거 확인. XML/로그/manifest는 `.local/cycle105-proof`에 보존한다. 기존 deprecated/unchecked/JVM CDS 경고는 남는다.

`git diff --check`·발췌/상대 링크 대조. 새 라이브러리/DB 스키마 없음. BTC 실제 노드·금융/보안 전체·자동 검색/운영 조회·Rust·Kafka/브라우저는 이번에 재실행하지 않았다. 실제 사업자 실사·승인된 설정 배포·키/망·공식 Cipher/Sandbox/LIVE 확인은 수행하지 않았다.

## 남은 업무

자동 입금 정보 교환은 아직 연결하지 않았다. 현재 고객/KYC·검색/체인/같은 주소의 출력 모호성 확인, 개인정보 제공 의도/결과의 암호화 영속 기록과 필수 감사·중복/불명 처리, 반환 이름/생일의 업무 검증, 금융 해제는 이어서 완성한다. 이 허용은 정보 제공 상대 정책이며 고객의 거래/출금 권한·자금 출처·제재 심사를 대체하지 않는다.


## 관련 커밋과 증적

- [구현 0737de5](https://github.com/GiHoon1123/Cex-Platform-Service/commit/0737de566475143e239eb66a64c200b98a8f4743)
- [격리 실행 증적](../reference/105-codevasp-counterparty-policy-verification-2026-10-25.json)
