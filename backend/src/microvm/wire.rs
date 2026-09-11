//! Bounded, versioned control messages. Guest messages never select host paths.
use crate::error::{Error, Result};
use serde_json::Value;
use tokio::io::{AsyncBufRead, AsyncBufReadExt, AsyncWrite, AsyncWriteExt};

pub const PORT: u32 = 5200;
pub const MAX_MESSAGE: usize = 2_000_000;

pub async fn read(reader: &mut (impl AsyncBufRead + Unpin)) -> Result<Option<Value>> {
    let mut bytes = Vec::new();
    loop {
        let chunk = reader.fill_buf().await?;
        if chunk.is_empty() {
            return if bytes.is_empty() {
                Ok(None)
            } else {
                Err(Error::bad("Truncated VM message."))
            };
        }
        let end = chunk.iter().position(|b| *b == b'\n');
        let count = end.map_or(chunk.len(), |n| n + 1);
        if bytes.len() + count > MAX_MESSAGE {
            return Err(Error::bad("VM message exceeds limit."));
        }
        bytes.extend_from_slice(&chunk[..count]);
        reader.consume(count);
        if end.is_some() {
            return Ok(Some(serde_json::from_slice(&bytes)?));
        }
    }
}

pub async fn write(writer: &mut (impl AsyncWrite + Unpin), value: &Value) -> Result<()> {
    let mut bytes = serde_json::to_vec(value)?;
    if bytes.len() >= MAX_MESSAGE {
        return Err(Error::bad("VM message exceeds limit."));
    }
    bytes.push(b'\n');
    writer.write_all(&bytes).await?;
    writer.flush().await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn rejects_truncation_and_bounds_frames() {
        assert!(read(&mut &b"{\"x\":1}"[..]).await.is_err());
        assert!(
            read(&mut vec![b'x'; MAX_MESSAGE + 1].as_slice())
                .await
                .is_err()
        );
        let mut source = &b"{\"v\":1}\n{\"v\":2}\n"[..];
        assert_eq!(read(&mut source).await.unwrap().unwrap()["v"], 1);
        assert_eq!(read(&mut source).await.unwrap().unwrap()["v"], 2);
        assert!(read(&mut source).await.unwrap().is_none());
    }
}
