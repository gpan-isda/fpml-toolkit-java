var data,toggle=false
function insRow(name){
toggle=false
var tbl = document.getElementById(name);
var lastRow = tbl.rows.length;
if(lastRow!=2)
    toggle=true
if(toggle){

var tbl = document.getElementById(name);
var lastRow = tbl.rows.length;
oneRow = tbl.rows[0]
data=tbl.rows[0].cells[1]
var x = tbl.insertRow(lastRow);
var y=x.insertCell(0)
y.innerHTML=data.innerHTML
oneRow.deleteCell(1) 
toggle=false

}
else
{

var tbl = document.getElementById(name);
var lastRow = tbl.rows.length;
if(lastRow>0)
{
data=tbl.rows[1].cells[0]
oneRow = tbl.rows[0]
newCell = oneRow.insertCell(1)
newCell.innerHTML=data.innerHTML
tbl.deleteRow(1)
toggle=true
}
}

}
var sectionHeight;
    function togglePannelStatus(content,tableName,sectionName)
    {
    
      if(content.style){
        var expand = (content.style.display=="none");
        content.style.display = (expand ? "block" : "none");
        }
        else
        {
        if(tableName!=null)
        {
                content= document.getElementById(tableName);
                if(content!=null)
                {
                 var expand = (content.style.display=="none");
                 content.style.display = (expand ? "block" : "none");
                 }
        }
          if(sectionName!=""){
                                   var content= document.getElementById(sectionName);
                                   var expand= content.style.visibility ;
                                   if(expand!="")
                                            {
                                                 if(expand=="visible")
                                                         { content.style.visibility = "hidden";
                                                          sectionHeight=content.style.height;
                                                          content.style.height=0;}
                                                 else
                                                         {  content.style.visibility = "visible";
                                                            content.style.height=sectionHeight;}
                                             }
                                  else
                                            { content.style.visibility = "hidden";
                                                sectionHeight=content.style.height;
                                              content.style.height=0;
                                            }
                            }
                   
        
        
          
          }
    }

          var toggle=true;
      function toggleTable()
      {
     if(toggle)
     {
       var elem =    document.getElementsByName("toggle");
       for(i=0;i<=elem.length-1;i++)
          {


                    var browserName=navigator.appName; 
                    if (browserName=="Netscape")
                    { 
                 
                     elem[i].attributes[2].nodeValue="Toggle View -->Side-by-Side";
                    }
                    else 
                    { 
                     if (browserName=="Microsoft Internet Explorer")
                     {
                     
                      elem[i].attributes[3].nodeValue="Toggle View -->Side-by-Side";
                     }
                     else
                      {
                      
                       }
                    }

        /*  elem[i].attributes[3].nodeValue="Toggle View -->Side-by-Side"*/
        /*  elem[i].attributes[2].nodeValue="Toggle View -->Side-by-Side";*/

          }
     }
     else
     {
     var elem =document.getElementsByName("toggle");
          for(i=0;i<=elem.length-1;i++)
          {
               
                    var browserName1=navigator.appName; 
                    if (browserName1=="Netscape")
                    { 
                     
                      elem[i].attributes[2].nodeValue="Toggle View -->Vertical";
                    }
                    else 
                    { 
                     if (browserName1=="Microsoft Internet Explorer")
                     {
                    
                      elem[i].attributes[3].nodeValue="Toggle View -->Vertical";
                     }
                     else
                      {
                       
                       }
                    }

            /*elem[i].attributes[3].nodeValue="Toggle View -->Vertical";*/
           /* elem[i].attributes[2].nodeValue="Toggle View -->Vertical";*/

          }
     }
     
       var tbl = document.getElementsByTagName('table');
       var i=0;
                           for (i=0;i<=tbl.length-1;i++)
                           {
                              if(toggle)
                              {
                                 if(tbl[i].id!="")
                                 { 
                                 var lastRow = tbl[i].rows.length;
                                 oneRow = tbl[i].rows[0]
                                 data=tbl[i].rows[0].cells[1];
                                 var x = tbl[i].insertRow(lastRow);
                                 var y=x.insertCell(0)
                                 y.innerHTML=data.innerHTML
                                 oneRow.deleteCell(1) 
                                 }
                              }
                              else
                              {
                              if(tbl[i].id)
                                    {
                                     var lastRow = tbl[i].rows.length;
                                       if(lastRow>0)
                                       {
                                       data=tbl[i].rows[1].cells[0]
                                       oneRow = tbl[i].rows[0]
                                       newCell = oneRow.insertCell(1)
                                       newCell.innerHTML=data.innerHTML
                                       tbl[i].deleteRow(1)
                                       }
                                    }
                               }
                           }
                         
                           if(toggle)
                              toggle=false;
                            else
                              toggle=true;
                       
       }
           
