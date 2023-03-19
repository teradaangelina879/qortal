console.log("Gateway mode");

window.addEventListener("message", (event) => {
    if (event == null || event.data == null || event.data.length == 0) {
        return;
    }
    if (event.data.action == null || event.data.requestedHandler == null) {
        return;
    }
    if (event.data.requestedHandler != "UI") {
        // Gateway mode only cares about requests that were intended for the UI
        return;
    }

    let response;
    let data = event.data;

    switch (data.action) {
        case "GET_USER_ACCOUNT":
        case "PUBLISH_QDN_RESOURCE":
        case "SEND_CHAT_MESSAGE":
        case "JOIN_GROUP":
        case "DEPLOY_AT":
        case "GET_WALLET_BALANCE":
        case "SEND_COIN":
            const errorString = "Authentication was requested, but this is not yet supported when viewing via a gateway. To use interactive features, please access using the Qortal UI desktop app. More info at: https://qortal.org";
            alert(errorString);
            response = "{\"error\": \"" + errorString + "\"}"
            break;

        default:
            console.log('Unhandled gateway message: ' + JSON.stringify(data));
            return;
    }

    handleResponse(event, response);

}, false);