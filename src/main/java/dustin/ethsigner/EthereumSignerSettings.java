package dustin.ethsigner;

import dustin.cex.domains.ethereum.EthereumSettings;

/** 별도 지급 프로세스만 읽는 서명 RPC와 고정 수탁 송신 주소다. */
public record EthereumSignerSettings(EthereumSettings node, String from) {
    public EthereumSignerSettings {
        from = EthereumSettings.address(from);
    }
}
