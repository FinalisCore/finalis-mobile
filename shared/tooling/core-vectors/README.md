# core-vectors

`sighash_vector.cpp` produces `shared/test-vectors/tx/sighash-v1-testnet.json` using finalis-core's
own `chain_domain_from_hex`, `signing_message_for_input` (`SC-SIG-V1`), `Tx::serialize` and address
encoding. `FinalisTxCodecCoreVectorTest` checks the Android codec against it byte for byte.

Regenerate against a built finalis-core checkout (`CORE` = repo root, `B` = its build dir):

```sh
g++ -std=c++20 -DSC_HAS_LIBSODIUM=1 -DSC_HAS_ROCKSDB=1 -DSC_HAS_SECP256K1=1 -DSC_HAS_SECP256K1_ZKP=1 \
  -I"$CORE/src" -I"$B/_deps/secp256k1_zkp-src/include" sighash_vector.cpp -o sighash_vector \
  "$B/libfinalis_core.a" -lssl -lcrypto -lsodium "$B/secp256k1-zkp/lib/libsecp256k1.a" -lrocksdb -lz -lpthread
./sighash_vector > ../../test-vectors/tx/sighash-v1-testnet.json
```

Generated from finalis-core v2.1.0 (testnet-2 genesis `4faa7e1b…`).
