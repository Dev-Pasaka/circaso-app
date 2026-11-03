# Fee spike: one signed challenge that pays receiver + Circaso fee

Date: 2026-11-03. Read-only on both repos. Labels used below:
**[TESTNET]** checked against Circle's testnet API with the test key (`TEST_API_KEY:` prefix, read from `circaso/.env`; never printed),
**[DOCS]** read in Circle's docs, **[INFERRED]** my reading, not proven.

## What we support today (from code)
`integrations/impl/.../CustodyChainsConfig.kt`: chains `BASE-SEPOLIA` (default), `MATIC-AMOY`, `ETH-SEPOLIA`, `SOL-DEVNET`
(production: `BASE`, `MATIC`, `ETH`, `SOL`), USDC on each. Account type is SCA on EVM and EOA on Solana. Today's
endpoints: `POST /v1/w3s/user/transactions/transfer` (`TransferBody`: one `destinationAddress`, `amounts`,
`tokenAddress`, `blockchain`, `feeLevel`, `refId`), `GET /v1/w3s/transactions/{id}`, `validateAddress`, plus user, PIN and wallet calls.
We call no estimate or contract-execution endpoint yet.

## How I tested
- The test key works (`GET /v1/w3s/config/entity` answered). I didn't create a new user: a new user has no wallet until it
  signs a PIN challenge, so it can't make transaction challenges. Instead I got a user token for an **existing sandbox
  test user** with PIN set and LIVE wallets on all four chains. Its Base Sepolia SCA is deployed and holds about 12.7 test USDC;
  its Amoy and Sepolia SCAs are empty and not yet deployed; its Solana EOA is empty.
- I created challenges and **did not sign them** (signing needs the PIN in the SDK). They expire on their own and move nothing.
  The created challenge ids are not kept here.

---

## EVM chains (Base, Polygon, Ethereum): all three behave the same

### 1. Contract execution in one user challenge: YES
- `POST /v1/w3s/user/transactions/contractExecution` is a user challenge flow, and on EVM the contract call is **one user operation**.
  Fields [DOCS] (https://developers.circle.com/api-reference/wallets/user-controlled-wallets/create-user-transaction-contract-execution-challenge):
  `idempotencyKey` (required), `contractAddress` (required), either `abiFunctionSignature` + `abiParameters` **or**
  `callData` (0x hex; you can't send both), `amount` (native value, payable calls only), `walletId` (or
  `walletAddress` + `blockchain`), `refId`, and for fees either `feeLevel` (LOW/MEDIUM/HIGH) or
  `gasLimit` + (`maxFee` + `priorityFee` | `gasPrice`). With `feeLevel`, a `gasLimit` override applies to **EOA only**,
  so for SCA we use `feeLevel` and Circle sets the user-op gas.
  Testnet chains listed: ETH-SEPOLIA, AVAX-FUJI, MATIC-AMOY, ARB-SEPOLIA, UNI-SEPOLIA, BASE-SEPOLIA, OP-SEPOLIA, ARC-TESTNET,
  MONAD-TESTNET. **No Solana.**
- An own `pay(token,to,amount,feeTo,fee,transferId)` contract would work through this endpoint. The contract needs a USDC
  allowance. Approval and pay **can be one challenge** with `executeBatch([approve(payContract, amount+fee), pay(...)])`.
  Circle documents exactly this shape for approve + CCTP `depositForBurn` [DOCS] (https://developers.circle.com/wallets/batch-operations.md).
  So we need neither a separate approval step nor a permit.
- [TESTNET] `contractExecution/estimateFee` for `pay(address,address,uint256,address,uint256,bytes32)` on a placeholder
  address was **accepted** (Circle simulates; calling an address with no code just succeeds, so this only proves the
  request shape, not a real contract).

### 2. Batched calls on our SCA wallets through the user-controlled API: YES (and it's the simplest option)
- [DOCS] https://developers.circle.com/wallets/batch-operations.md: "Batching is available on any smart contract account
  (SCA or MSCA) through a `contractExecution` endpoint", including user-controlled
  `POST /user/transactions/contractExecution` "(uses the challenge approval flow)". Every SCA has
  `executeBatch((address target, uint256 value, bytes callData)[])`. You call it with `contractAddress` = **the wallet's own address**.
  "every call succeeds together or the whole batch reverts". The docs give "Two USDC transfers" as their first example.
  EOAs can't do this. Modular Wallets (MSCA, passkeys, its own SDK with batch and parallel user ops) is a separate product
  and not needed. Developer-controlled SCAs use the same body plus `entitySecretCiphertext`.
- Request we would send [TESTNET, accepted on all three EVM testnets]:
  ```json
  {
    "idempotencyKey": "<uuid>", "walletId": "<sender SCA wallet id>", "feeLevel": "MEDIUM", "refId": "<transferId>",
    "contractAddress": "<sender SCA address>",
    "abiFunctionSignature": "executeBatch((address,uint256,bytes)[])",
    "abiParameters": [[
      ["<USDC address>", "0", "0xa9059cbb<receiver padded><amount padded>"],
      ["<USDC address>", "0", "0xa9059cbb<feeWallet padded><fee padded>"]
    ]]
  }
  ```
  Response: `{"data":{"challengeId":"<uuid>"}}` on BASE-SEPOLIA (deployed wallet), MATIC-AMOY and ETH-SEPOLIA (undeployed wallets).
- No approval is needed: the wallet calls `USDC.transfer` itself.
- [TESTNET] The estimate **simulates the whole batch**. When the second call overdraws, Circle answers
  `{"code":155208,"message":"Estimate fee execution reverted.","errors":[{"message":"ERC20: transfer amount exceeds balance"}]}`,
  so a quote that estimates fine also checks "both transfers would succeed right now".

### 4. Gas Station and gas estimation (EVM)
- [DOCS] https://developers.circle.com/wallets/gas-station.md: Gas Station uses ERC-4337 paymasters on EVM and needs SCA wallets.
  It supports user-controlled wallets on Base, Polygon, Ethereum, Arbitrum, OP, Avalanche, Unichain, Arc and Monad, mainnet and testnet.
  Billing (https://developers.circle.com/wallets/gas-station/billing.md): gas plus a **5 % processing fee**, billed on the 1st of the month
  or when $100 of gas has built up, whichever comes first. Pay within 7 days (or before another $20 builds up), or **all
  policies pause** and mainnet sponsorship stops.
- Policies (https://developers.circle.com/wallets/gas-station/policy-management.md): one **default policy per network**. It covers
  "any transaction on a specific network, including wallet creation and contract execution", so **contract execution
  and batches are covered**. Optional limits: max USD per day, max USD per transaction, max operations per day, blocked
  addresses. A transaction outside the policy is **not sponsored and fails** (our SCAs hold no native gas). The per-transaction cap
  does not apply to a wallet's first transaction (deployment). Testnet has an automatic default policy with daily
  limits (Base Sepolia 50 ETH, Amoy 50 MATIC, Sepolia 50 ETH, Solana Devnet 2 SOL). **Mainnet policies must be created
  and activated in the console per chain** (task for Pasaka).
- Estimating: `POST /v1/w3s/transactions/contractExecution/estimateFee` (same body as the challenge, minus
  idempotency and fee fields; needs `X-User-Token`) and `POST /v1/w3s/transactions/transfer/estimateFee`
  (https://developers.circle.com/api-reference/wallets/user-controlled-wallets/create-transaction-estimate-fee,
  https://developers.circle.com/api-reference/wallets/user-controlled-wallets/create-transfer-estimate-fee).
  The response has `low/medium/high` × {`gasLimit`, `baseFee`, `priorityFee`, `maxFee` (gwei), `networkFee`, `networkFeeRaw`
  (both in the **native token**: ETH / POL)}, plus for SCA the ERC-4337 fields `callGasLimit`, `verificationGasLimit`,
  `preVerificationGas`. `networkFee` is the upper bound; `networkFeeRaw` "has a lower buffer, closer to actual".
- [TESTNET] numbers (MEDIUM):

  | Chain / call | gasLimit | callGas | verificationGas | networkFee (native) |
  |---|---|---|---|---|
  | Base Sepolia, plain USDC transfer (deployed SCA) | 248,231 | 65,711 | 48,796 | 0.00000304 ETH |
  | Base Sepolia, **executeBatch 2 transfers** | 322,280 | 132,813 | 49,620 | 0.00000371 ETH |
  | Amoy, executeBatch (undeployed SCA) | 620,027 | 206,809 | **273,449** | 0.0279 POL |
  | Sepolia, executeBatch (undeployed SCA) | 1,373,860 | 108,788 | **1,139,842** | 0.000172 ETH |

  - The batch costs about 30 % more gas than a plain transfer on Base. A past real sponsored Base Sepolia transfer from this wallet
    recorded `estimatedFee.networkFee` 0.00000285 against actual `networkFee` 0.00000133, so the estimate runs about 2× high on Base.
  - [INFERRED] The estimate includes **lazy deployment** for a wallet's first outbound transaction (the large
    `verificationGasLimit` on the undeployed wallets) and paymaster overhead (`gasLimit` is about 36 k more than the sum of the three
    4337 fields). The response says nothing about sponsorship; it is the gas Gas Station will pay. We can treat it as our
    cost, as long as we convert native to USD with our own price feed (Circle gives no USD amount).
  - The actual gas per payment is on the completed transaction (`networkFee` and `estimatedFee` in `GET /transactions/{id}`),
    which feeds the "actual gas" column in the fee ledger.
- Cost note [DOCS] (https://developers.circle.com/wallets/gas-fees.md): SCA on Ethereum mainnet is about $3.7 per transfer and
  about $6.3 for deployment (2024 prices), so with the 25 % rule the minimum fee on ETH is roughly $15 to $40. Circle itself says
  "For Ethereum mainnet, use EOA" (https://developers.circle.com/wallets/account-types.md). Base and Polygon cost cents or less.

### 5. EVM recommendation: **batch (`executeBatch` on the sender's own SCA)** for Base, Polygon and Ethereum
- One challenge, atomic, sponsored, no approval, **no contract to write, deploy or audit**, and the same code on every EVM chain.
  `refId` = our `transferId`, and the receiver and fee legs show as two USDC `Transfer` events in one transaction hash.
- Keep the own `pay()` contract only if we later need an on-chain `transferId` event or on-chain enforcement of `feeTo`.
  Since the server builds the batch (fee address from server config, never from the client), that isn't needed now.
- Ethereum: works, but gas makes the minimum fee high; product call (keep it, or set a high `min_fee_usd`).

---

## Solana (SOL-DEVNET / SOL), EOA wallets

### 1 and 2. Contract execution or batch: NO
- [DOCS] https://developers.circle.com/wallets/supported-blockchains.md: "Solana: Contract execution is not supported."
  [TESTNET] `POST /user/transactions/contractExecution` on SOL-DEVNET answers
  `{"code":156027,..."CONTRACT_EXECUTION unsupported for SOL-DEVNET"}`.
- The transfer API takes **one** `destinationAddress` (`amounts` is per token, not per recipient), so a transfer challenge can't
  carry the fee leg.

### 3. Two instructions in one challenge: YES at challenge level, through the sign-transaction API (with caveats)
- [DOCS] `POST /v1/w3s/user/sign/transaction`
  (https://developers.circle.com/api-reference/wallets/user-controlled-wallets/sign-user-transaction) supports SOL, SOL-DEVNET, EVM and
  EVM-TESTNET. Body: `walletId` (or `walletAddress` + `blockchain`), `rawTransaction` (base64 for Solana), `memo` (text
  shown to the user). After the PIN, Circle **returns the signature and does not broadcast**: we broadcast and track it ourselves
  (https://developers.circle.com/wallets/signing-apis.md).
- [TESTNET] A hand-built legacy Solana transaction with **two instructions** (two 0-lamport system transfers,
  fresh devnet blockhash) was accepted: `{"data":{"challengeId":"<uuid>"}}`. Also accepted: the same transaction with
  **another account as fee payer** (the wallet as a second signer). Not verified: what the SDK returns after signing a
  multi-signer transaction (we expect the wallet's partial signature) and whether it then broadcasts cleanly.
- A real fee transaction would be: `createAssociatedTokenAccountIdempotent` for receiver and fee wallet if needed, plus two
  `transferChecked` USDC instructions. Atomic by Solana's rules.

### Who pays SOL fees
- [DOCS] Gas Station on Solana uses Circle fee-payer accounts and is listed for user-controlled wallets on Solana mainnet and devnet
  (https://developers.circle.com/wallets/gas-station.md, account-types: "Gas sponsorship: Solana only, using feePayer").
  Circle's blog describes Gas Station adding its fee-payer signature to transactions **Circle builds and broadcasts**
  (https://www.circle.com/blog/how-circles-gas-station-uses-fee-payers-to-enable-gasless-transactions-on-solana).
  ATA rent sponsorship is a **permissioned feature** (ask Circle) with abuse terms
  (https://developers.circle.com/wallets/gas-station/solana-ata-sponsorship.md).
- [INFERRED] A raw transaction we sign and broadcast ourselves is **not sponsored** by Gas Station. Then the fee payer must
  be the user's wallet (needs SOL: bad UX) or **Circaso's own Solana fee wallet** (developer-controlled, co-signing with
  the developer sign-transaction API, which supports Solana EOAs). That fee wallet then also pays ATA rent, and the 25 % rule
  is easy to keep (about $0.0006 base + priority per transaction).

### Gas estimate on Solana
- [TESTNET] `transfer/estimateFee` on the empty devnet wallet failed (`155210 Fails to perform transaction estimation`;
  native: `155258 insufficient`), so I couldn't check the shape. For the sign-transaction path, Circle doesn't estimate at all:
  we compute the fee ourselves (signatures × 5,000 lamports + priority fee × compute units, plus rent for any new ATA) with
  `getFeeForMessage`.

### 5. Solana recommendation: **wait** (no fee on Solana at launch), with a spike-2 path ready
- No Circle-native atomic option exists. The only atomic one is a self-built transaction through `sign/transaction`,
  which brings in our own broadcasting and status tracking, our own fee payer, and a **blockhash that expires in about 60–90 s**
  while our signing window is 15 minutes. We'd need a **durable nonce** account per wallet, or build the transaction after the PIN
  screen opens, which the current challenge flow doesn't allow.
- If Solana fees are wanted before Circle adds batch transfers: run a short spike 2 (sign a two-instruction transaction with a durable
  nonce and the Circaso fee payer on a phone, broadcast it, check Circle still indexes the inbound legs), then decide.

---

## Overall recommendation
| Network | One challenge can hold | Gas | Choose |
|---|---|---|---|
| Base | `executeBatch` of 2 USDC transfers (also any contract call, or approve + call) | Gas Station, sponsored; estimate via `contractExecution/estimateFee` | **Batch** |
| Polygon | same | same | **Batch** |
| Ethereum | same | same; expensive, so a high minimum fee | **Batch** (or drop or limit ETH) |
| Solana | 1 transfer (transfer API), or any raw tx (sign API, we broadcast, no sponsorship) | Circle fee payer only for Circle-built txs | **Wait** (spike 2 if needed) |

Quote flow on EVM: build the exact `executeBatch` body, call `contractExecution/estimateFee` with the user token and take MEDIUM
`networkFee` (upper bound) × our native price × safety margin as the gas used in `fee = max(rate × base, 4 × gas)`. Then create the
challenge with the **same body** and `feeLevel: MEDIUM`. The estimate also works as a pre-check that both legs succeed.

## Open risks
- **What the person sees when signing:** for contract execution, the Circle SDK confirmation (if we show it) describes a contract
  call, not "Send $200 + $1 fee". Check on a device; we may rely on our own review screen before the PIN.
- **Webhooks and parsing:** the batch arrives as a contract execution (`operation` CONTRACT_EXECUTION) with no single
  `destinationAddress`/`amounts`. `CircleTransactions.translate` (built for transfers) needs a path for it, and the receiver's
  inbound leg must still show as INBOUND for Circaso wallets. [INFERRED; verify with one signed testnet batch.]
- **First transaction deploys the SCA**, so gas is several times higher (Sepolia 1.37 M gas). The quote covers it because the estimate
  includes it, but that first payment's minimum fee jumps; consider absorbing deployment gas as cost.
- **Gas Station policy limits on mainnet:** too tight and payments fail, not just go unsponsored. Unpaid invoice → all policies pause.
- **Price moves between quote and signing (15 min):** use `networkFee` (buffered) plus margin; alert when actual exceeds 25 %.
- **Own `pay()` contract** (only if chosen later): needs an audit, per-chain deployment, and a USDC allowance; approve + pay can share one batch.
- **Solana:** blockhash expiry vs 15-minute window, self-broadcast, fee-payer key custody, ATA rent abuse.
- **Not verified on testnet:** signing and execution of any of these (needs the PIN in the SDK); exact Solana partial-signature
  behaviour; how batch webhooks look.

## Sources
- https://developers.circle.com/wallets/batch-operations.md
- https://developers.circle.com/api-reference/wallets/user-controlled-wallets/create-user-transaction-contract-execution-challenge
- https://developers.circle.com/api-reference/wallets/user-controlled-wallets/create-transaction-estimate-fee
- https://developers.circle.com/api-reference/wallets/user-controlled-wallets/create-transfer-estimate-fee
- https://developers.circle.com/api-reference/wallets/user-controlled-wallets/create-user-transaction-transfer-challenge
- https://developers.circle.com/api-reference/wallets/user-controlled-wallets/sign-user-transaction
- https://developers.circle.com/wallets/signing-apis.md , https://developers.circle.com/wallets/sign-transactions.md
- https://developers.circle.com/wallets/gas-station.md , .../gas-station/policy-management.md , .../gas-station/billing.md ,
  .../gas-station/solana-ata-sponsorship.md
- https://developers.circle.com/wallets/gas-fees.md , https://developers.circle.com/wallets/account-types.md ,
  https://developers.circle.com/wallets/supported-blockchains.md , https://developers.circle.com/release-notes/wallets-2026.md
- https://www.circle.com/blog/how-circles-gas-station-uses-fee-payers-to-enable-gasless-transactions-on-solana
