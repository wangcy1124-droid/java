// JSR223 compiled once; do not interpolate JMeter variables in script source.
vars.put('orderId', '')
try {
    def body = new groovy.json.JsonSlurper().parseText(prev.getResponseDataAsString())
    if (prev.getResponseCode() != '200' || body.success != true || !(body.data instanceof Number)) {
        AssertionResult.setFailure(true)
        AssertionResult.setFailureMessage('Purchase rejected: HTTP ' + prev.getResponseCode() + ' ' + (body.errorMsg ?: 'invalid order response'))
    } else {
        vars.put('orderId', body.data.toString())
    }
} catch (Exception ignored) {
    AssertionResult.setFailure(true)
    AssertionResult.setFailureMessage('Non-JSON purchase response, HTTP ' + prev.getResponseCode())
}
