# --- !Ups

create table job_heuristic_result (
  id                        integer auto_increment not null,
  job_job_id                varchar(50),
  severity                  integer,
  analysis_name             varchar(255),
  data                      longtext,
  data_columns              integer,
  constraint ck_job_heuristic_result_severity check (severity in ('2','4','1','3','0')),
  constraint pk_job_heuristic_result primary key (id))
;

create table job_result (
  job_id                    varchar(50) not null,
  username                  varchar(50),
  job_name                  varchar(100),
  start_time                bigint,
  analysis_time             bigint,
  severity                  integer,
  url                       varchar(200),
  constraint ck_job_result_severity check (severity in ('2','4','1','3','0')),
  constraint pk_job_result primary key (job_id))
;

alter table job_heuristic_result add constraint fk_job_heuristic_result_job_1 foreign key (job_job_id) references job_result (job_id) on delete restrict on update restrict;
create index ix_job_heuristic_result_job_1 on job_heuristic_result (job_job_id);
create index ix_job_result_username_1 on job_result (username);
create index ix_job_result_analysis_time_1 on job_result (analysis_time);



# --- !Downs

SET FOREIGN_KEY_CHECKS=0;

drop table job_heuristic_result;

drop table job_result;

SET FOREIGN_KEY_CHECKS=1;

