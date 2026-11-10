"""Local-only SFTP fixture. Run in a venv containing Paramiko; writes connection info to --info."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import socket
import threading
import time
import paramiko

parser = argparse.ArgumentParser()
parser.add_argument('--root', required=True)
parser.add_argument('--info', required=True)
args = parser.parse_args()
root = Path(args.root).resolve()
root.mkdir(parents=True, exist_ok=True)
info_file = Path(args.info)
host_key = paramiko.RSAKey.generate(2048)
user_key = paramiko.RSAKey.generate(2048)
user_key_file = root.parent / 'fixture-user-key.pem'
user_key.write_private_key_file(str(user_key_file), password='fixture-key-passphrase')
lock = threading.Lock()
auth_attempts = 0
listener = socket.socket()
listener.bind(('127.0.0.1', 0))
listener.listen(8)
info = {'port': listener.getsockname()[1], 'fingerprint': 'SHA256:' + base64.b64encode(hashlib.sha256(host_key.asbytes()).digest()).decode().rstrip('='), 'key': str(user_key_file), 'authAttempts': 0}

def write_info():
    temp = info_file.with_suffix('.part')
    temp.write_text(json.dumps(info))
    temp.replace(info_file)
write_info()

class Auth(paramiko.ServerInterface):
    def get_allowed_auths(self, username):
        return 'password,publickey'
    def attempt(self, valid):
        with lock:
            info['authAttempts'] += 1
            write_info()
        return paramiko.AUTH_SUCCESSFUL if valid else paramiko.AUTH_FAILED
    def check_auth_password(self, username, password):
        return self.attempt(username == 'fixture' and password == 'fixture-password')
    def check_auth_publickey(self, username, key):
        return self.attempt(username == 'fixture' and key == user_key)
    def check_channel_request(self, kind, chanid):
        return paramiko.OPEN_SUCCEEDED if kind == 'session' else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED

class Handle(paramiko.SFTPHandle):
    def stat(self):
        try:
            return paramiko.SFTPAttributes.from_stat(os.fstat((getattr(self, 'readfile', None) or self.writefile).fileno()))
        except OSError as error:
            return paramiko.SFTPServer.convert_errno(error.errno)

class Store(paramiko.SFTPServerInterface):
    def path(self, value):
        result = (root / value.lstrip('/')).resolve()
        if result != root and root not in result.parents:
            raise PermissionError('Outside fixture')
        return result
    def canonicalize(self, path):
        return '/' + str(self.path(path).relative_to(root)) if path not in ('/', '.', '') else '/'
    def list_folder(self, path):
        try:
            result = []
            for item in self.path(path).iterdir():
                attrs = paramiko.SFTPAttributes.from_stat(item.lstat())
                attrs.filename = item.name
                result.append(attrs)
            return result
        except OSError as error:
            return paramiko.SFTPServer.convert_errno(error.errno)
    def stat(self, path):
        try: return paramiko.SFTPAttributes.from_stat(self.path(path).stat())
        except OSError as error: return paramiko.SFTPServer.convert_errno(error.errno)
    def lstat(self, path):
        try: return paramiko.SFTPAttributes.from_stat(self.path(path).lstat())
        except OSError as error: return paramiko.SFTPServer.convert_errno(error.errno)
    def open(self, path, flags, attr):
        try:
            fd = os.open(self.path(path), flags, 0o600)
            mode = 'r+b' if flags & os.O_RDWR else 'wb' if flags & os.O_WRONLY else 'rb'
            stream = os.fdopen(fd, mode)
            handle = Handle(flags)
            if not flags & os.O_WRONLY: handle.readfile = stream
            if flags & (os.O_RDWR | os.O_WRONLY): handle.writefile = stream
            return handle
        except OSError as error: return paramiko.SFTPServer.convert_errno(error.errno)
    def chattr(self, path, attr):
        try:
            if attr.st_mode is not None: os.chmod(self.path(path), attr.st_mode)
            return paramiko.SFTP_OK
        except OSError as error: return paramiko.SFTPServer.convert_errno(error.errno)
    def mkdir(self, path, attr):
        try: self.path(path).mkdir(); return paramiko.SFTP_OK
        except OSError as error: return paramiko.SFTPServer.convert_errno(error.errno)
    def remove(self, path):
        try: self.path(path).unlink(); return paramiko.SFTP_OK
        except OSError as error: return paramiko.SFTPServer.convert_errno(error.errno)
    def rename(self, old, new):
        try:
            if self.path(new).exists(): return paramiko.SFTP_FAILURE
            self.path(old).rename(self.path(new)); return paramiko.SFTP_OK
        except OSError as error: return paramiko.SFTPServer.convert_errno(error.errno)
    def posix_rename(self, old, new):
        try: os.replace(self.path(old), self.path(new)); return paramiko.SFTP_OK
        except OSError as error: return paramiko.SFTPServer.convert_errno(error.errno)

class Server(paramiko.SFTPServer):
    def _send_server_version(self):
        from paramiko.sftp import CMD_INIT, CMD_VERSION
        from paramiko.message import Message
        packet, data = self._read_packet()
        if packet != CMD_INIT: raise paramiko.SFTPError('Expected init')
        message = Message()
        message.add_int(3)
        message.add('posix-rename@openssh.com', '1')
        self._send_packet(CMD_VERSION, message)
        return 3

def serve(client):
    transport = paramiko.Transport(client)
    try:
        transport.add_server_key(host_key)
        transport.set_subsystem_handler('sftp', Server, Store)
        transport.start_server(server=Auth())
        while transport.is_active(): time.sleep(.05)
    except (EOFError, OSError, paramiko.SSHException):
        pass
    finally:
        transport.close()

while True:
    client, _ = listener.accept()
    threading.Thread(target=serve, args=(client,), daemon=True).start()
