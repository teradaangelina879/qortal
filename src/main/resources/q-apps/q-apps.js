function httpGet(url) {
    var request = new XMLHttpRequest();
    request.open("GET", url, false);
    request.send(null);
    return request.responseText;
}

function handleResponse(event, response) {
    if (event == null) {
        return;
    }

    // Handle emmpty or missing responses
    if (response == null || response.length == 0) {
        response = "{\"error\": \"Empty response\"}"
    }

    // Parse response
    let responseObj;
    try {
        responseObj = JSON.parse(response);
    } catch (e) {
        // Not all responses will be JSON
        responseObj = response;
    }

    // Respond to app
    if (responseObj.error != null) {
        event.ports[0].postMessage({
            result: null,
            error: responseObj
        });
    }
    else {
        event.ports[0].postMessage({
            result: responseObj,
            error: null
        });
    }
}

window.addEventListener("message", (event) => {
    if (event == null || event.data == null || event.data.length == 0) {
        return;
    }
    if (event.data.action == null) {
        // This could be a response from the UI
        handleResponse(event, event.data);
    }
    if (event.data.requestedHandler != null && event.data.requestedHandler === "UI") {
        // This request was destined for the UI, so ignore it
        return;
    }

    console.log("Core received event: " + JSON.stringify(event.data));

    let url;
    let response;
    let data = event.data;

    switch (data.action) {
        case "GET_ACCOUNT_DATA":
            response = httpGet("/addresses/" + data.address);
            break;

        case "GET_ACCOUNT_NAMES":
            response = httpGet("/names/address/" + data.address);
            break;

        case "GET_NAME_DATA":
            response = httpGet("/names/" + data.name);
            break;

        case "SEARCH_QDN_RESOURCES":
            url = "/arbitrary/resources?";
            if (data.service != null) url = url.concat("&service=" + data.service);
            if (data.identifier != null) url = url.concat("&identifier=" + data.identifier);
            if (data.default != null) url = url.concat("&default=" + data.default);
            if (data.nameListFilter != null) url = url.concat("&namefilter=" + data.nameListFilter);
            if (data.includeStatus != null) url = url.concat("&includestatus=" + new Boolean(data.includeStatus).toString());
            if (data.includeMetadata != null) url = url.concat("&includemetadata=" + new Boolean(data.includeMetadata).toString());
            if (data.limit != null) url = url.concat("&limit=" + data.limit);
            if (data.offset != null) url = url.concat("&offset=" + data.offset);
            if (data.reverse != null) url = url.concat("&reverse=" + new Boolean(data.reverse).toString());
            response = httpGet(url);
            break;

        case "FETCH_QDN_RESOURCE":
            url = "/arbitrary/" + data.service + "/" + data.name;
            if (data.identifier != null) url = url.concat("/" + data.identifier);
            url = url.concat("?");
            if (data.filepath != null) url = url.concat("&filepath=" + data.filepath);
            if (data.rebuild != null) url = url.concat("&rebuild=" + new Boolean(data.rebuild).toString())
            response = httpGet(url);
            break;

        case "GET_QDN_RESOURCE_STATUS":
            url = "/arbitrary/resource/status/" + data.service + "/" + data.name;
            if (data.identifier != null) url = url.concat("/" + data.identifier);
            response = httpGet(url);
            break;

        case "SEARCH_CHAT_MESSAGES":
            url = "/chat/messages?";
            if (data.before != null) url = url.concat("&before=" + data.before);
            if (data.after != null) url = url.concat("&after=" + data.after);
            if (data.txGroupId != null) url = url.concat("&txGroupId=" + data.txGroupId);
            if (data.involving != null) data.involving.forEach((x, i) => url = url.concat("&involving=" + x));
            if (data.reference != null) url = url.concat("&reference=" + data.reference);
            if (data.chatReference != null) url = url.concat("&chatreference=" + data.chatReference);
            if (data.hasChatReference != null) url = url.concat("&haschatreference=" + new Boolean(data.hasChatReference).toString());
            if (data.limit != null) url = url.concat("&limit=" + data.limit);
            if (data.offset != null) url = url.concat("&offset=" + data.offset);
            if (data.reverse != null) url = url.concat("&reverse=" + new Boolean(data.reverse).toString());
            response = httpGet(url);
            break;

        case "LIST_GROUPS":
            url = "/groups?";
            if (data.limit != null) url = url.concat("&limit=" + data.limit);
            if (data.offset != null) url = url.concat("&offset=" + data.offset);
            if (data.reverse != null) url = url.concat("&reverse=" + new Boolean(data.reverse).toString());
            response = httpGet(url);
            break;

        case "GET_BALANCE":
            url = "/addresses/balance/" + data.address;
            if (data.assetId != null) url = url.concat("&assetId=" + data.assetId);
            response = httpGet(url);
            break;

        case "GET_AT":
            url = "/at" + data.atAddress;
            response = httpGet(url);
            break;

        case "GET_AT_DATA":
            url = "/at/" + data.atAddress + "/data";
            response = httpGet(url);
            break;

        case "LIST_ATS":
            url = "/at/byfunction/" + data.codeHash58 + "?";
            if (data.isExecutable != null) url = url.concat("&isExecutable=" + data.isExecutable);
            if (data.limit != null) url = url.concat("&limit=" + data.limit);
            if (data.offset != null) url = url.concat("&offset=" + data.offset);
            if (data.reverse != null) url = url.concat("&reverse=" + new Boolean(data.reverse).toString());
            response = httpGet(url);
            break;

        case "FETCH_BLOCK":
            if (data.signature != null) {
                url = "/blocks/" + data.signature;
            }
            else if (data.height != null) {
                url = "/blocks/byheight/" + data.height;
            }
            url = url.concat("?");
            if (data.includeOnlineSignatures != null) url = url.concat("&includeOnlineSignatures=" + data.includeOnlineSignatures);
            response = httpGet(url);
            break;

        case "FETCH_BLOCK_RANGE":
            url = "/blocks/range/" + data.height + "?";
            if (data.count != null) url = url.concat("&count=" + data.count);
            if (data.reverse != null) url = url.concat("&reverse=" + data.reverse);
            if (data.includeOnlineSignatures != null) url = url.concat("&includeOnlineSignatures=" + data.includeOnlineSignatures);
            response = httpGet(url);
            break;

        case "SEARCH_TRANSACTIONS":
            url = "/transactions/search?";
            if (data.startBlock != null) url = url.concat("&startBlock=" + data.startBlock);
            if (data.blockLimit != null) url = url.concat("&blockLimit=" + data.blockLimit);
            if (data.txGroupId != null) url = url.concat("&txGroupId=" + data.txGroupId);
            if (data.txType != null) data.txType.forEach((x, i) => url = url.concat("&txType=" + x));
            if (data.address != null) url = url.concat("&address=" + data.address);
            if (data.confirmationStatus != null) url = url.concat("&confirmationStatus=" + data.confirmationStatus);
            if (data.limit != null) url = url.concat("&limit=" + data.limit);
            if (data.offset != null) url = url.concat("&offset=" + data.offset);
            if (data.reverse != null) url = url.concat("&reverse=" + new Boolean(data.reverse).toString());
            response = httpGet(url);
            break;

        case "GET_PRICE":
            url = "/crosschain/price/" + data.blockchain + "?";
            if (data.maxtrades != null) url = url.concat("&maxtrades=" + data.maxtrades);
            if (data.inverse != null) url = url.concat("&inverse=" + data.inverse);
            response = httpGet(url);
            break;

        default:
            // Pass to parent (UI), in case they can fulfil this request
            event.data.requestedHandler = "UI";
            parent.postMessage(event.data, '*', [event.ports[0]]);
            return;
    }

    handleResponse(event, response);

}, false);

const awaitTimeout = (timeout, reason) =>
    new Promise((resolve, reject) =>
        setTimeout(
            () => (reason === undefined ? resolve() : reject(reason)),
            timeout
        )
    );

/**
 * Make a Qortal (Q-Apps) request with no timeout
 */
const qortalRequestWithNoTimeout = (request) => new Promise((res, rej) => {
    const channel = new MessageChannel();

    channel.port1.onmessage = ({data}) => {
        channel.port1.close();

        if (data.error) {
            rej(data.error);
        } else {
            res(data.result);
        }
    };

    window.postMessage(request, '*', [channel.port2]);
});

/**
 * Make a Qortal (Q-Apps) request with the default timeout (10 seconds)
 */
const qortalRequest = (request) =>
    Promise.race([qortalRequestWithNoTimeout(request), awaitTimeout(10000, "The request timed out")]);

/**
 * Make a Qortal (Q-Apps) request with a custom timeout, specified in milliseconds
 */
const qortalRequestWithTimeout = (request, timeout) =>
    Promise.race([qortalRequestWithNoTimeout(request), awaitTimeout(timeout, "The request timed out")]);