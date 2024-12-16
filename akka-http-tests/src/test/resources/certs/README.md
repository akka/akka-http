Test cert files created through:

```shell
$ export PW="verysecret"

# delete the existing jks:es
$ rm *.jks
# create a fake CA
$ keytool -genkeypair -v \
    -alias exampleca \
    -dname "CN=exampleCA, OU=Example Org, O=Example Company, L=San Francisco, ST=California, C=US" \
    -keystore exampleca.jks \
    -keypass:env PW \
    -storepass:env PW \
    -keyalg RSA \
    -keysize 4096 \
    -ext KeyUsage:critical="keyCertSign" \
    -ext BasicConstraints:critical="ca:true" \
    -validity 999900

$ keytool -export -v \
    -alias exampleca \
    -file exampleca.crt \
    -keypass:env PW \
    -storepass:env PW \
    -keystore exampleca.jks \
    -rfc
    
# create a server keypair, in the JKS store example.com.jks
$ keytool -genkeypair -v \
    -alias example.com \
    -dname "CN=example.com, OU=Example Org, O=Example Company, L=San Francisco, ST=California, C=US" \
    -keystore example.com.jks \
    -keypass:env PW \
    -storepass:env PW \
    -keyalg RSA \
    -keysize 2048 \
    -validity 999900
    
# create a signing request for the server cert (.csr file)
$ keytool -certreq -v \
    -alias example.com \
    -keypass:env PW \
    -storepass:env PW \
    -keystore example.com.jks \
    -file example.com.csr

# as the CA, sign the server cert, producing `example.com.crt`
$ keytool -gencert -v \
    -alias exampleca \
    -keypass:env PW \
    -storepass:env PW \
    -keystore exampleca.jks \
    -infile example.com.csr \
    -outfile example.com.crt \
    -ext KeyUsage:critical="digitalSignature,keyEncipherment" \
    -ext EKU="serverAuth" \
    -ext SAN="DNS:example.com" \
    -rfc

# import the CA cert into a JKS keystore
$ keytool -import -v \
    -alias exampleca \
    -file exampleca.crt \
    -keystore example.com.jks \
    -storetype JKS \
    -storepass:env PW
# enter yes

# import the signed server cert into the same JKS keystore
$ keytool -import -v \
    -alias example.com \
    -file example.com.crt \
    -keystore example.com.jks \
    -storetype JKS \
    -storepass:env PW

# we already have the crt file, but dump it out again from the keystore to get the whole certificate chain (I _think)
$ keytool -export -v \
    -alias example.com \
    -file example.com.crt \
    -keypass:env PW \
    -storepass:env PW \
    -keystore example.com.jks \
    -rfc

# Create a PKCS12 keystore with the server cert and private key
$ keytool -importkeystore -v \
    -srcalias example.com \
    -srckeystore example.com.jks \
    -srcstoretype jks \
    -srcstorepass:env PW \
    -destkeystore example.com.p12 \
    -destkeypass:env PW \
    -deststorepass:env PW \
    -deststoretype PKCS12

# export the private key into a pem file from the PKCS12 keystore
$ openssl pkcs12 \
    -nocerts \
    -nodes \
    -passout env:PW \
    -passin env:PW \
    -in example.com.p12 \
    -out example.com.key
# then manually remove the lines before the "-----BEGIN CERTIFICATE-----" lines in example.com.key

# Finally drop that intermediate p12 keystore (we may need that later, but for now)
$ rm example.com.p12

# create a client trust store with the CA cert
$ keytool -import -v \
    -alias exampleca \
    -file exampleca.crt \
    -keypass:env PW \
    -storepass changeit \
    -keystore exampletrust.jks
    
# create a client cert
$ keytool -genkeypair -v \
    -alias client1 \
    -dname "CN=client1, OU=Akka Team, O=Lightbend, L=Stockholm, ST=Svealand, C=SE" \
    -keystore client1.jks \
    -keypass:env PW \
    -storepass:env PW \
    -keyalg RSA \
    -keysize 2048 \
    -validity 999900

# signing request
$ keytool -certreq -v \
    -alias client1 \
    -keypass:env PW \
    -storepass:env PW \
    -keystore client1.jks \
    -file client1.csr

# CA creates cert from signing request, adding some SAN (subject alternative names)
# I think those could also be added in the previous request step
$ keytool -gencert -v \
    -alias exampleca \
    -keypass:env PW \
    -storepass:env PW \
    -keystore exampleca.jks \
    -ext san=ip:127.0.0.1,dns:localhost \
    -infile client1.csr \
    -outfile client1.crt \
    -rfc

# import ca to client keystore (needs the full cert chain for importing the cert itself)
$ keytool -import -v \
    -alias exampleca \
    -file exampleca.crt \
    -keystore client1.jks \
    -storetype JKS \
    -storepass:env PW
# enter yes

# import signed cert into client keystore
$ keytool -import -v \
    -alias client1 \
    -file client1.crt \
    -keystore client1.jks \
    -storetype JKS \
    -storepass:env PW

# FIXME not sure what we do here, we already have the crt file, but dump it out again from that crt
$ keytool -export -v \
    -alias client1 \
    -file client1.crt \
    -keypass:env PW \
    -storepass:env PW \
    -keystore client1.jks \
    -rfc
 
# export key and cert to p12
$ keytool -importkeystore -v \
    -srcalias client1 \
    -srckeystore client1.jks \
    -srcstoretype jks \
    -srcstorepass:env PW \
    -destkeystore client1.p12 \
    -destkeypass:env PW \
    -deststorepass:env PW \
    -deststoretype PKCS12
    
# export the private client key into a pem file from the PKCS12 keystore
$ openssl pkcs12 \
    -nocerts \
    -nodes \
    -passout env:PW \
    -passin env:PW \
    -in client1.p12 \
    -out client1.key
# then manually remove the lines before the "-----BEGIN CERTIFICATE-----" lines in client1.key
    
# drop that intermediate p12 keystore (we may need that later, but for now)
$ rm client1.p12

# create a client cert that we trust with different CN and SAN (mostly using openssl this time)    
$ openssl genrsa -passout pass:"" -out client2.key 4096
$ openssl req -new -key client2.key -out client2.csr -passin pass:"" \
    -days 999900 \
    -subj "/C=SE/ST=StateName/L=CityName/O=CompanyName/OU=CompanySectionName/CN=client2/"

$ keytool -gencert -v \
    -alias exampleca \
    -keypass:env PW \
    -storepass:env PW \
    -keystore exampleca.jks \
    -ext san=ip:192.168.0.1,dns:some.example.com \
    -infile client2.csr \
    -outfile client2.crt \
    -rfc
# private key to pem
$ openssl rsa -in client2.key -out client2.key -passin pass:""

# create a client cert that we don't trust 
$ openssl genrsa -aes256 -passout pass:xxxx -out untrustedca.pass.key 4096
$ openssl rsa -passin pass:xxxx -in untrustedca.pass.key -out untrustedca.key
$ rm untrustedca.pass.key
$ openssl req -new -x509 -days 999900 -key untrustedca.key -out untrustedca.pem -subj "/C=XX/ST=StateName/L=CityName/O=CompanyName/OU=CompanySectionName/CN=CommonNameOrHostname"
$ openssl genrsa -passout pass:"" -out untrusted-client1.key 4096
# signing request, use the same san and CN as the trusted cert
$ openssl req -new -key untrusted-client1.key -out untrusted-client1.csr -passin pass:"" \
    -subj "/C=XX/ST=StateName/L=CityName/O=CompanyName/OU=CompanySectionName/CN=client1/" \
    -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1'
$ openssl x509 -req -days 999900 -in untrusted-client1.csr -CA untrustedca.pem -CAkey untrustedca.key -passin pass:xxxx -set_serial 01 -out untrusted-client1.pem
# private key to pem
$ openssl rsa -in untrusted-client1.key -out untrusted-client1.key -passin pass:""
```