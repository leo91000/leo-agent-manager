//! Durable replay and live delivery share one cursor. Notifications are hints;
//! SQLite is authoritative, so a slow subscriber never buffers or blocks writes.
use crate::{
    error::{Error, Result},
    http::{Input, cookie},
    service::Service,
    store::Db,
    validation::{text, uuid},
};
use axum::response::{
    IntoResponse, Response,
    sse::{Event, KeepAlive, Sse},
};
use serde_json::{Value, json};
use std::{sync::Arc, time::Duration};

#[derive(Clone)]
struct Scope {
    chat: bool,
    id: String,
}
struct Page {
    state: Value,
    events: Vec<crate::store::Event>,
    reset: bool,
    more: bool,
}

async fn page(s: &Service, scope: Scope, after: i64) -> Result<Page> {
    s.store
        .read(move |db| {
            // A single SQLite snapshot covers metadata and the event boundary.
            let tx = rusqlite::Transaction::new_unchecked(
                db.0,
                rusqlite::TransactionBehavior::Deferred,
            )?;
            let db = Db(&tx);
            let chat = if scope.chat && !scope.id.is_empty() {
                crate::chats::detail(&db, &scope.id)?
            } else {
                Value::Null
            };
            let run = if scope.chat {
                chat["run"].clone()
            } else {
                crate::error::required(db.run(&scope.id)?, "Run not found")?
            };
            let run_id = text(&run, "id");
            let max: i64 = db.0.query_row(
                "SELECT COALESCE(MAX(id),0) FROM events WHERE run_id=?",
                [run_id],
                |r| r.get(0),
            )?;
            let reset = after > max;
            // Stop reading rows at the byte budget, rather than allocating an
            // entire page of large historical outputs for every subscriber.
            let events = db.event_batch(run_id, if reset { 0 } else { after }, 100, 256 * 1024)?;
            let cursor = events
                .last()
                .map_or(if reset { 0 } else { after }, |e| e.id);
            let mut artifacts: Vec<_> = if run_id.is_empty() {
                Vec::new()
            } else {
                db.keys(&format!("artifact:{run_id}:"))?
                    .into_iter()
                    .map(|(_, v)| v)
                    .collect()
            };
            artifacts.sort_by_key(|v| v["createdAt"].as_i64().unwrap_or(0));
            let mut state = json!({"run":run,"chat":chat,"artifacts":artifacts});
            if scope.chat {
                state["chats"] = crate::chats::list(&db)?.into();
            }
            Ok(Page {
                state,
                events,
                reset,
                more: cursor < max,
            })
        })
        .await
}

pub async fn http(s: Arc<Service>, kind: &str, id: &str, input: Input) -> Result<Response> {
    if input.method != "GET" {
        return Err(Error::new(405, "Method not allowed."));
    }
    if !id.is_empty() {
        uuid(id)?;
    }
    let after = match input.headers.get("last-event-id") {
        Some(value) => value
            .to_str()
            .ok()
            .and_then(|v| v.parse::<i64>().ok())
            .filter(|v| *v >= 0)
            .ok_or_else(|| Error::bad("Invalid event cursor."))?,
        None => input.number("after", 0, 0, i64::MAX)?,
    };
    let scope = Scope {
        chat: kind == "chats",
        id: id.to_owned(),
    };
    // Subscribe before reading: commits during replay remain observable.
    let changes = s.store.subscribe();
    let first = page(&s, scope.clone(), after).await?;
    let session = cookie(&input.headers);
    let subscription = Subscription {
        s,
        scope,
        changes,
        cursor: after,
        pending: Some(first),
        previous: Value::Null,
        session,
        deltas: crate::live_text::TextDeltas::default(),
    };
    let stream = futures_util::stream::try_unfold(subscription, |mut subscription| async move {
        let event = subscription.next().await?;
        Ok::<_, Error>(event.map(|event| (event, subscription)))
    });
    Ok((
        [("x-accel-buffering", "no")],
        Sse::new(stream).keep_alive(
            KeepAlive::new()
                .interval(Duration::from_secs(10))
                .event(Event::default().event("ping").data("{}")),
        ),
    )
        .into_response())
}

struct Subscription {
    s: Arc<Service>,
    scope: Scope,
    changes: tokio::sync::watch::Receiver<u64>,
    cursor: i64,
    pending: Option<Page>,
    previous: Value,
    session: String,
    deltas: crate::live_text::TextDeltas,
}
impl Subscription {
    async fn next(&mut self) -> Result<Option<Event>> {
        loop {
            if self.s.shutdown.is_cancelled() {
                return Ok(None);
            }
            if self.s.auth.read(&self.session).await?.is_none() {
                return Ok(None);
            }
            let mut current = match self.pending.take() {
                Some(first) => first,
                None => {
                    self.changes.borrow_and_update();
                    page(&self.s, self.scope.clone(), self.cursor).await?
                }
            };
            let run_changed = !self.previous.is_null()
                && self.previous["run"]["id"] != current.state["run"]["id"];
            if run_changed {
                current = page(&self.s, self.scope.clone(), 0).await?;
            }
            let reset = current.reset || run_changed;
            if reset {
                self.cursor = 0;
            }
            let changed = self.previous != current.state;
            let has_events = !current.events.is_empty();
            self.cursor = current.events.last().map_or(self.cursor, |e| e.id);
            let mut data = json!({"events":self.deltas.encode(current.events, reset)?,"reset":reset,"more":current.more});
            if changed {
                data["state"] = current.state.clone();
            }
            self.previous = current.state;
            if changed || has_events || reset {
                let event = Event::default()
                    .event("batch")
                    .id(self.cursor.to_string())
                    .json_data(data)
                    .map_err(Error::internal)?;
                // Coalesce rapid commits without an unbounded per-client queue.
                tokio::time::sleep(Duration::from_millis(25)).await;
                return Ok(Some(event));
            }
            tokio::select! {
                _ = self.s.shutdown.cancelled() => return Ok(None),
                result = self.changes.changed() => if result.is_err() { return Ok(None); },
                // Revalidate session expiry and recover writes by an external
                // maintenance process; normal delivery is notification driven.
                _ = tokio::time::sleep(Duration::from_secs(15)) => {},
            }
        }
    }
}
