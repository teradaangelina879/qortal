# Qortal Project - Q-Apps Documentation

## Introduction

Q-Apps are static web apps written in javascript, HTML, CSS, and other static assets. The key difference between a Q-App and a fully static site is its ability to interact with both the logged-in user and on-chain data. This is achieved using the API described in this document.


# Section 1: Simple links and image loading via HTML

## Section 1a: Linking to other QDN websites / resources

The `qortal://` protocol can be used to access QDN data from within Qortal websites and apps. The basic format is as follows:
```
<a href="qortal://{service}/{name}/{identifier}/{path}">link text</a>
```

However, the system will support the omission of the `identifier` and/or `path` components to allow for simpler URL formats.

A simple link to another website can be achieved with this HTML code:
```
<a href="qortal://WEBSITE/QortalDemo">link text</a>
```

To link to a specific page of another website:
```
<a href="qortal://WEBSITE/QortalDemo/minting-leveling/index.html">link text</a>
```

To link to a standalone resource, such as an avatar
```
<a href="qortal://THUMBNAIL/QortalDemo/qortal_avatar">avatar</a>
```

For cases where you would prefer to explicitly include an identifier (to remove ambiguity) you can use the keyword `default` to access a resource that doesn't have an identifier. For instance:
```
<a href="qortal://WEBSITE/QortalDemo/default">link to root of website</a>
<a href="qortal://WEBSITE/QortalDemo/default/minting-leveling/index.html">link to subpage of website</a>
```


## Section 1b: Linking to other QDN images

The same applies for images, such as displaying an avatar:
```
<img src="qortal://THUMBNAIL/QortalDemo/qortal_avatar" />
```

...or even an image from an entirely different website:
```
<img src="qortal://WEBSITE/AlphaX/assets/img/logo.png" />
```


# Section 2: Integrating a Javascript app

Javascript apps allow for much more complex integrations with Qortal's blockchain data.

## Section 2a: Direct API calls

The standard [Qortal Core API](http://localhost:12391/api-documentation) is available to websites and apps, and can be called directly using a standard AJAX request, such as:
```
async function getNameInfo(name) {
    const response = await fetch("/names/" + name);
    const nameData = await response.json();
    console.log("nameData: " + JSON.stringify(nameData));
}
getNameInfo("QortalDemo");
```

However, this only works for read-only data, such as looking up transactions, names, balances, etc. Also, since the address of the logged in account can't be retrieved from the core, apps can't show personalized data with this approach.


## Section 2b: User interaction via qortalRequest()

To take things a step further, the qortalRequest() function can be used to interact with the user, in order to:

- Request address and public key of the logged in account
- Publish data to QDN
- Send chat messages
- Join groups
- Deploy ATs (smart contracts)
- Send QORT or any supported foreign coin

In addition to the above, qortalRequest() also supports many read-only functions that are also available via direct core API calls. Using qortalRequest() helps with futureproofing, as the core APIs can be modified without breaking functionality of existing Q-Apps.


### Making a request

Qortal core will automatically inject the `qortalRequest()` javascript function to all websites/apps, which returns a Promise. This can be used to fetch data or publish data to the Qortal blockchain. This functionality supports async/await, as well as try/catch error handling.

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

### Timeouts

By default, all requests will timeout after a certain amount of time (default 10 seconds, but some actions use a higher value), and will throw an error - `The request timed out`. If you need a longer timeout - e.g. when fetching large QDN resources that may take a long time to be retrieved, you can use `qortalRequestWithTimeout(request, timeout)` as an alternative to `qortalRequest(request)`.

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

# Section 3: qortalRequest Documentation

## Supported actions

Here is a list of currently supported actions:
- GET_USER_ACCOUNT
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
- GET_QDN_RESOURCE_URL
- LINK_TO_QDN_RESOURCE

More functionality will be added in the future.

## Example Requests

Here are some example requests for each of the above:

### Get address of logged in account
_Will likely require user approval_
```
let account = await qortalRequest({
     action: "GET_USER_ACCOUNT"
});
let address = account.address;
```

### Get public key of logged in account
_Will likely require user approval_
```
let pubkey = await qortalRequest({
     action: "GET_USER_ACCOUNT"
});
let publicKey = account.publicKey;
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

### Send QORT to address
_Requires user approval_
```
await qortalRequest({
    action: "SEND_COIN",
    coin: "QORT",
    destinationAddress: "QZLJV7wbaFyxaoZQsjm6rb9MWMiDzWsqM2",
    amount: 1.00000000 // 1 QORT
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
    creationBytes: "12345", // Must be Base58 encoded
    name: "test name",
    description: "test description",
    type: "test type",
    tags: "test tags",
    amount: 1.00000000, // 1 QORT
    assetId: 0,
    // fee: 0.002 // optional - will use default fee if excluded
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

### Get URL to load a QDN resource
```
let url = await qortalRequest({
    action: "GET_QDN_RESOURCE_URL",
    service: "THUMBNAIL",
    name: "QortalDemo",
    identifier: "qortal_avatar"
    // path: "filename.jpg" // optional - not needed if resource contains only one file
});
```

### Get URL to load a QDN website
```
let url = await qortalRequest({
    action: "GET_QDN_RESOURCE_URL",
    service: "WEBSITE",
    name: "QortalDemo",
});
```

### Get URL to load a specific file from a QDN website
```
let url = await qortalRequest({
    action: "GET_QDN_RESOURCE_URL",
    service: "WEBSITE",
    name: "AlphaX",
    path: "/assets/img/logo.png"
});
```

### Link/redirect to another QDN website
Note: an alternate method is to include `<a href="qortal://WEBSITE/QortalDemo">link text</a>` within your HTML code.
```
let res = await qortalRequest({
    action: "LINK_TO_QDN_RESOURCE",
    service: "WEBSITE",
    name: "QortalDemo",
});
```

### Link/redirect to a specific path of another QDN website
Note: an alternate method is to include `<a href="qortal://WEBSITE/QortalDemo/minting-leveling/index.html">link text</a>` within your HTML code.
```
let res = await qortalRequest({
    action: "LINK_TO_QDN_RESOURCE",
    service: "WEBSITE",
    name: "QortalDemo",
    path: "/minting-leveling/index.html"
});
```


# Section 4: Examples

## Sample App

Here is a sample application to display the logged-in user's avatar:
```
<html>
<head>
    <script>
        async function showAvatar() {
            try {
                // Get QORT address of logged in account
                let account = await qortalRequest({
                    action: "GET_USER_ACCOUNT"
                });
                let address = account.address;
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


# Section 5: Testing and Development

Publishing an in-development app to mainnet isn't recommended. There are several options for developing and testing a Q-app before publishing to mainnet:

### Preview mode

Select "Preview" in the UI after choosing the zip. This allows for full Q-App testing without the need to publish any data.


### Testnets

For an end-to-end test of Q-App publishing, you can use the official testnet, or set up a single node testnet of your own (often referred to as devnet) on your local machine. See [Single Node Testnet Quick Start Guide](TestNets.md#quick-start).