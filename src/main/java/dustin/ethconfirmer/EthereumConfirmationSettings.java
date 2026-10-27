package dustin.ethconfirmer;

import dustin.cex.domains.ethereum.EthereumSettings;

/** 별도 읽기 프로세스가 사용할 관찰 노드와 필요한 확정 깊이다. */
public record EthereumConfirmationSettings(EthereumSettings node, int required) {
    public EthereumConfirmationSettings {
        if (required < 1 || required > 2048) throw new IllegalArgumentException("이더리움 출금 확정 깊이를 확인하세요");
    }
}
