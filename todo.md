### Progress resume/save

+ Dump msg_progress table
+ Count msg ids from 1

On addWork:
1. if progress(peer).max_id < work.ids.max => update progress (mem and db)
2. add work to msg_work (in single transaction)

On restore:
1. if msg_work isn't empty => run tasks (do not update msg_progress)
2. if last msg id higher than one from msg_progress => download progress.max_id -> last msg_id
 