/* Rewrite requests for {{sub}}.example.com/{{path}} to
 * s3://{{bucket}}/{{sub}}/{{path}}
*/

exports.handler = async (event, context) => {
    const record = event.Records[0].cf;
    const request = record.request;
    const host = request.headers['host'][0].value;

    if (host.endsWith(`.${SUBDOMAIN_BASE}`)) {
        const subdomain = host.split('.')[0];
        request.uri = `/${subdomain}${request.uri}`;
    } else {
        throw new Error(`Host does not match the expected domain: ${host} does not match *.${SUBDOMAIN_BASE}`);
    }

    if (request.uri.endsWith('/')) {
        request.uri += 'index.html';
    }

    console.log("Routing to " + request.uri);

    return request;
};
