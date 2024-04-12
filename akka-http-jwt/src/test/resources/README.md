# Instructions on how to generate public key and JWT used in tests


## Generate private key

```bash
openssl genrsa -out private.key 2048
```

## Generate public key
```bash
openssl rsa -in private.key -pubout -out my-public.key
```

## Generate JWT

Write below to header.txt file:
```bash
echo '{"alg": "RS256","typ": "JWT"}' > header.txt
```

Write below to payload.txt file:
```bash
echo '{"sub": "1234567890","name": "John Doe","admin": true}' > payload.txt
```

Create JWT by running below commands:
```bash
cat header.txt | tr -d '\n' | tr -d '\r' | base64 | tr +/ -_ | tr -d '=' > header.b64
cat payload.txt | tr -d '\n' | tr -d '\r' | base64 | tr +/ -_ |tr -d '=' > payload.b64
printf "%s" "$(<header.b64)" "." "$(<payload.b64)" > unsigned.b64
rm header.b64
rm payload.b64
openssl dgst -sha256 -sign private.key -out sig.txt unsigned.b64
cat sig.txt | base64 | tr +/ -_ | tr -d '=' > sig.b64
printf "%s" "$(<unsigned.b64)" "." "$(<sig.b64)" > my-jwt-token.txt
rm unsigned.b64
rm sig.b64
rm sig.txt
```

Remove unwanted files:
```bash
rm private.key
rm header.txt
rm payload.txt
```
