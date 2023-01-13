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
            response = httpGet("/apps/account?address=" + data.address);
            break;

        case "GET_ACCOUNT_NAMES":
            response = httpGet("/apps/account/names?address=" + data.address);
            break;

        case "GET_NAME_DATA":
            response = httpGet("/apps/name?name=" + data.name);
            break;

        case "SEARCH_QDN_RESOURCES":
            url = "/apps/resources?";
            if (data.service != null) url = url.concat("&service=" + data.service);
            if (data.identifier != null) url = url.concat("&identifier=" + data.identifier);
            if (data.default != null) url = url.concat("&default=" + data.default);
            if (data.nameListFilter != null) url = url.concat("&nameListFilter=" + data.nameListFilter);
            if (data.includeStatus != null) url = url.concat("&includeStatus=" + new Boolean(data.includeStatus).toString());
            if (data.includeMetadata != null) url = url.concat("&includeMetadata=" + new Boolean(data.includeMetadata).toString());
            if (data.limit != null) url = url.concat("&limit=" + data.limit);
            if (data.offset != null) url = url.concat("&offset=" + data.offset);
            if (data.reverse != null) url = url.concat("&reverse=" + new Boolean(data.reverse).toString());
            response = httpGet(url);
            break;

        case "FETCH_QDN_RESOURCE":
            url = "/apps/resource?";
            if (data.service != null) url = url.concat("&service=" + data.service);
            if (data.name != null) url = url.concat("&name=" + data.name);
            if (data.identifier != null) url = url.concat("&identifier=" + data.identifier);
            if (data.filepath != null) url = url.concat("&filepath=" + data.filepath);
            if (data.rebuild != null) url = url.concat("&rebuild=" + new Boolean(data.rebuild).toString())
            response = httpGet(url);
            break;

        case "GET_QDN_RESOURCE_STATUS":
            url = "/apps/resourcestatus?";
            if (data.service != null) url = url.concat("&service=" + data.service);
            if (data.name != null) url = url.concat("&name=" + data.name);
            if (data.identifier != null) url = url.concat("&identifier=" + data.identifier);
            response = httpGet(url);
            break;

        case "SEARCH_CHAT_MESSAGES":
            url = "/apps/chatmessages?";
            if (data.before != null) url = url.concat("&before=" + data.before);
            if (data.after != null) url = url.concat("&after=" + data.after);
            if (data.txGroupId != null) url = url.concat("&txGroupId=" + data.txGroupId);
            if (data.involving != null) data.involving.forEach((x, i) => url = url.concat("&involving=" + x));
            if (data.reference != null) url = url.concat("&reference=" + data.reference);
            if (data.chatReference != null) url = url.concat("&chatReference=" + data.chatReference);
            if (data.hasChatReference != null) url = url.concat("&hasChatReference=" + new Boolean(data.hasChatReference).toString());
            if (data.limit != null) url = url.concat("&limit=" + data.limit);
            if (data.offset != null) url = url.concat("&offset=" + data.offset);
            if (data.reverse != null) url = url.concat("&reverse=" + new Boolean(data.reverse).toString());
            response = httpGet(url);
            break;

        case "LIST_GROUPS":
            url = "/apps/groups?";
            if (data.limit != null) url = url.concat("&limit=" + data.limit);
            if (data.offset != null) url = url.concat("&offset=" + data.offset);
            if (data.reverse != null) url = url.concat("&reverse=" + new Boolean(data.reverse).toString());
            response = httpGet(url);
            break;

        case "GET_BALANCE":
            url = "/apps/balance?";
            if (data.assetId != null) url = url.concat("&assetId=" + data.assetId);
            if (data.address != null) url = url.concat("&address=" + data.address);
            response = httpGet(url);
            break;

        case "GET_AT":
            url = "/apps/at?";
            if (data.atAddress != null) url = url.concat("&atAddress=" + data.atAddress);
            response = httpGet(url);
            break;

        case "GET_AT_DATA":
            url = "/apps/atdata?";
            if (data.atAddress != null) url = url.concat("&atAddress=" + data.atAddress);
            response = httpGet(url);
            break;

        case "LIST_ATS":
            url = "/apps/ats?";
            if (data.codeHash58 != null) url = url.concat("&codeHash58=" + data.codeHash58);
            if (data.isExecutable != null) url = url.concat("&isExecutable=" + data.isExecutable);
            if (data.limit != null) url = url.concat("&limit=" + data.limit);
            if (data.offset != null) url = url.concat("&offset=" + data.offset);
            if (data.reverse != null) url = url.concat("&reverse=" + new Boolean(data.reverse).toString());
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