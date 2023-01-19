# Qortal Project - Q-Apps Documentation

## Introduction

Q-Apps are static web apps written in javascript, HTML, CSS, and other static assets. The key difference between a Q-App and a fully static site is its ability to interact with both the logged-in user and on-chain data. This is achieved using the API described in this document.



## Making a request

Qortal core  will automatically inject a `qortalRequest()` javascript function to all websites/apps, which returns a Promise. This can be used to fetch data or publish data to the Qortal blockchain. This functionality supports async/await, as well as try/catch error handling.

```
async function myfunction() {
    try {
        let res = await qortalRequest({
            action: "GET_ACCOUNT_DATA",
            address: "QZLJV7wbaFyxaoZQsjm6rb9MWMiDzWsqM2"
        });
        console.log(JSON.stringify(res)); // Log the response to the console
    
    } catch(e) {
        console.log("Error: " + JSON.stringify(e));
    }
}
myfunction();
```

## Timeouts

By default, all requests will timeout after 10 seconds, and will throw an error - `The request timed out`. If you need a longer timeout - e.g. when fetching large QDN resources that may take a long time to be retrieved, you can use `qortalRequestWithTimeout(request, timeout)` as an alternative to `qortalRequest(request)`.

```
async function myfunction() {
    try {
        let timeout = 60000; // 60 seconds
        let res = await qortalRequestWithTimeout({
            action: "FETCH_QDN_RESOURCE",
            name: "QortalDemo",
            service: "THUMBNAIL",
            identifier: "qortal_avatar"
        }, timeout);
        
        // Do something with the avatar here
    
    } catch(e) {
        console.log("Error: " + JSON.stringify(e));
    }
}
myfunction();
```

## Supported actions

Here is a list of currently supported actions:
- GET_ACCOUNT_ADDRESS
- GET_ACCOUNT_PUBLIC_KEY
- GET_ACCOUNT_DATA
- GET_ACCOUNT_NAMES
- GET_NAME_DATA
- SEARCH_QDN_RESOURCES
- GET_QDN_RESOURCE_STATUS
- FETCH_QDN_RESOURCE
- PUBLISH_QDN_RESOURCE
- GET_WALLET_BALANCE
- GET_BALANCE
- SEND_COIN
- SEARCH_CHAT_MESSAGES
- SEND_CHAT_MESSAGE
- LIST_GROUPS
- JOIN_GROUP
- DEPLOY_AT
- GET_AT
- GET_AT_DATA
- LIST_ATS
- FETCH_BLOCK
- FETCH_BLOCK_RANGE
- SEARCH_TRANSACTIONS
- GET_PRICE

More functionality will be added in the future.

## Example Requests

Here are some example requests for each of the above:

### Get address of logged in account
_Will likely require user approval_
```
let address = await qortalRequest({
     action: "GET_ACCOUNT_ADDRESS"
});
```

### Get public key of logged in account
_Will likely require user approval_
```
let pubkey = await qortalRequest({
     action: "GET_ACCOUNT_PUBLIC_KEY"
});
```

### Get account data
```
let res = await qortalRequest({
    action: "GET_ACCOUNT_DATA",
    address: "QZLJV7wbaFyxaoZQsjm6rb9MWMiDzWsqM2"
});
```

### Get names owned by account
```
let res = await qortalRequest({
    action: "GET_ACCOUNT_NAMES",
    address: "QZLJV7wbaFyxaoZQsjm6rb9MWMiDzWsqM2"
});
```

### Get name data
```
let res = await qortalRequest({
    action: "GET_NAME_DATA",
    name: "QortalDemo"
});
```


### Search QDN resources
```
let res = await qortalRequest({
    action: "SEARCH_QDN_RESOURCES",
    service: "THUMBNAIL",
    identifier: "qortal_avatar", // Optional
    default: true, // Optional
    nameListFilter: "FollowedNames", // Optional
    includeStatus: false,
    includeMetadata: false,
    limit: 100,
    offset: 0,
    reverse: true
});
```

### Fetch QDN single file resource
Data is returned in the base64 format
```
let res = await qortalRequest({
    action: "FETCH_QDN_RESOURCE",
    name: "QortalDemo",
    service: "THUMBNAIL",
    identifier: "qortal_avatar", // Optional. If omitted, the default resource is returned, or you can alternatively use the keyword "default"
    rebuild: false
});
```

### Fetch file from multi file QDN resource
Data is returned in the base64 format
```
let res = await qortalRequest({
    action: "FETCH_QDN_RESOURCE",
    name: "QortalDemo",
    service: "WEBSITE",
    identifier: "default", // Optional. If omitted, the default resource is returned, or you can alternatively request that using the keyword "default", as shown here
    filepath: "index.html", // Required only for resources containing more than one file
    rebuild: false
});
```

### Get QDN resource status
```
let res = await qortalRequest({
    action: "GET_QDN_RESOURCE_STATUS",
    name: "QortalDemo",
    service: "THUMBNAIL",
    identifier: "qortal_avatar" // Optional
});
```

### Publish QDN resource
_Requires user approval_
```
await qortalRequest({
    action: "PUBLISH_QDN_RESOURCE",
    name: "Demo", // Publisher must own the registered name - use GET_ACCOUNT_NAMES for a list
    service: "WEBSITE",
    data64: "base64_encoded_data",
    title: "Title",
    description: "Description",
    category: "TECHNOLOGY",
    tags: ["tag1", "tag2", "tag3", "tag4", "tag5"]
});
```

### Get wallet balance (QORT)
_Requires user approval_
```
await qortalRequest({
    action: "GET_WALLET_BALANCE",
    coin: "QORT"
});
```

### Get wallet balance (foreign coin)
_Requires user approval_
```
await qortalRequest({
    action: "GET_WALLET_BALANCE",
    coin: "LTC"
});
```

### Get address or asset balance
```
let res = await qortalRequest({
    action: "GET_BALANCE",
    address: "QZLJV7wbaFyxaoZQsjm6rb9MWMiDzWsqM2"
});
```
```
let res = await qortalRequest({
    action: "GET_BALANCE",
    assetId: 1,
    address: "QZLJV7wbaFyxaoZQsjm6rb9MWMiDzWsqM2"
});
```

### Send coin to address
_Requires user approval_
```
await qortalRequest({
    action: "SEND_COIN",
    coin: "QORT",
    destinationAddress: "QZLJV7wbaFyxaoZQsjm6rb9MWMiDzWsqM2",
    amount: 100000000, // 1 QORT
    fee: 10000 // 0.0001 QORT
});
```

### Send coin to address
_Requires user approval_
```
await qortalRequest({
    action: "SEND_COIN",
    coin: "LTC",
    destinationAddress: "LSdTvMHRm8sScqwCi6x9wzYQae8JeZhx6y",
    amount: 100000000, // 1 LTC
    fee: 20 // 0.00000020 LTC per byte
});
```

### Search or list chat messages
```
let res = await qortalRequest({
    action: "SEARCH_CHAT_MESSAGES",
    before: 999999999999999,
    after: 0,
    txGroupId: 0, // Optional (must specify either txGroupId or two involving addresses)
    // involving: ["QZLJV7wbaFyxaoZQsjm6rb9MWMiDzWsqM2", "QSefrppsDCsZebcwrqiM1gNbWq7YMDXtG2"], // Optional (must specify either txGroupId or two involving addresses)
    // reference: "reference", // Optional
    // chatReference: "chatreference", // Optional
    // hasChatReference: true, // Optional
    limit: 100,
    offset: 0,
    reverse: true
});
```

### Send a group chat message
_Requires user approval_
```
await qortalRequest({
    action: "SEND_CHAT_MESSAGE",
    groupId: 0,
    message: "Test"
});
```

### Send a private chat message
_Requires user approval_
```
await qortalRequest({
    action: "SEND_CHAT_MESSAGE",
    destinationAddress: "QZLJV7wbaFyxaoZQsjm6rb9MWMiDzWsqM2",
    message: "Test"
});
```

### List groups
```
let res = await qortalRequest({
    action: "LIST_GROUPS",
    limit: 100,
    offset: 0,
    reverse: true
});
```

### Join a group
_Requires user approval_
```
await qortalRequest({
    action: "JOIN_GROUP",
    groupId: 100
});
```


### Deploy an AT
_Requires user approval_
```
let res = await qortalRequest({
    action: "DEPLOY_AT",
    creationBytes: "12345",
    name: "test name",
    description: "test description",
    type: "test type",
    tags: "test tags",
    amount: 100000000, // 1 QORT
    assetId: 0,
    fee: 20000 // 0.0002 QORT
});
```

### Get AT info
```
let res = await qortalRequest({
    action: "GET_AT",
    atAddress: "ASRUsCjk6fa5bujv3oWYmWaVqNtvxydpPH"
});
```

### Get AT data bytes (base58 encoded)
```
let res = await qortalRequest({
    action: "GET_AT_DATA",
    atAddress: "ASRUsCjk6fa5bujv3oWYmWaVqNtvxydpPH"
});
```

### List ATs by functionality
```
let res = await qortalRequest({
    action: "LIST_ATS",
    codeHash58: "4KdJETRAdymE7dodDmJbf5d9L1bp4g5Nxky8m47TBkvA",
    isExecutable: true,
    limit: 100,
    offset: 0,
    reverse: true
});
```

### Fetch block by signature
```
let res = await qortalRequest({
    action: "FETCH_BLOCK",
    signature: "875yGFUy1zHV2hmxNWzrhtn9S1zkeD7SQppwdXFysvTXrankCHCz4iyAUgCBM3GjvibbnyRQpriuy1cyu953U1u5uQdzuH3QjQivi9UVwz86z1Akn17MGd5Z5STjpDT7248K6vzMamuqDei57Znonr8GGgn8yyyABn35CbZUCeAuXju"
});
```

### Fetch block by height
```
let res = await qortalRequest({
    action: "FETCH_BLOCK",
    height: "1139850"
});
```

### Fetch a range of blocks
```
let res = await qortalRequest({
    action: "FETCH_BLOCK_RANGE",
    height: "1139800",
    count: 20,
    reverse: false
});
```

### Search transactions
```
let res = await qortalRequest({
    action: "SEARCH_TRANSACTIONS",
    // startBlock: 1139000,
    // blockLimit: 1000,
    txGroupId: 0,
    txType: [
        "PAYMENT",
        "REWARD_SHARE"
    ],
    confirmationStatus: "CONFIRMED",
    limit: 10,
    offset: 0,
    reverse: false
});
```

### Get an estimate of the QORT price
```
let res = await qortalRequest({
    action: "GET_PRICE",
    blockchain: "LITECOIN",
    // maxtrades: 10,
    inverse: true
});
```


## Sample App

Here is a sample application to display the logged-in user's avatar:
```
<html>
<head>
    <script>
        async function showAvatar() {
            try {
                // Get QORT address of logged in account
                let address = await qortalRequest({
                    action: "GET_ACCOUNT_ADDRESS"
                });
                console.log("address: " + address);
            
                // Get names owned by this account
                let names = await qortalRequest({
                    action: "GET_ACCOUNT_NAMES",
                    address: address
                });
                console.log("names: " + JSON.stringify(names));
            
                if (names.size == 0) {
                    console.log("User has no registered names");
                    return;
                }
            
                // Download the avatar of the first registered name
                let avatar = await qortalRequest({
                    action: "FETCH_QDN_RESOURCE",
                    name: names[0].name,
                    service: "THUMBNAIL",
                    identifier: "qortal_avatar"
                });
                console.log("avatar: " + JSON.stringify(avatar));
            
                // Display the avatar image on the screen
                document.getElementsById("avatar").src = "data:image/png;base64," + avatar;
                
            } catch(e) {
                console.log("Error: " + JSON.stringify(e));
            }
        }
        showAvatar();
    </script>
</head>
<body>
    <img width="500" id="avatar" />
</body>
</html>
```


## Testing and Development

Publishing an in-development app to mainnet isn't recommended. There are several options for developing and testing a Q-app before publishing to mainnet:

### Preview mode

All read-only operations can be tested using preview mode. It can be used as follows:

1. Ensure Qortal core is running locally on the machine you are developing on. Previewing via a remote node is not currently possible.
2. Make a local API call to `POST /render/preview`, passing in the API key (found in apikey.txt), and the path to the root of your Q-App, for example:
```
curl -X POST "http://localhost:12391/render/preview" -H "X-API-KEY: apiKeyGoesHere" -d "/home/username/Websites/MyApp"
```
3. This returns a URL, which can be copied and pasted into a browser to view the preview
4. Modify the Q-App as required, then repeat from step 2 to generate a new preview URL

This is a short term method until preview functionality has been implemented within the UI.


### Single node testnet

For full read/write testing of a Q-App, you can set up a single node testnet (often referred to as devnet) on your local machine. See [Single Node Testnet Quick Start Guide](TestNets.md#quick-start).